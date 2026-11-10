package it.eone.connect.sync;

import it.eone.connect.db.Db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Da dove ripartire nella sincronizzazione di un'entita' (generalizza tabfcssync del bridge FCS).
 *
 * Uso tipico in un job:
 * <pre>
 *   SyncState.Run run = sync.begin("S4_PROD", "EKET");
 *   try {
 *       String from = run.watermark();                 // null = prima volta: sincronizzazione completa
 *       ... leggi dal sistema di origine le modifiche successive a "from", scrivi nel DB ...
 *       run.ok(nuovoWatermark, righeElaborate);        // il watermark avanza SOLO se tutto e' andato bene
 *   } catch (Exception e) {
 *       run.error(e.getMessage());                     // il prossimo giro riparte dallo stesso punto
 *       throw e;
 *   }
 * </pre>
 * Il watermark e' testo: un timestamp ISO, una data, una chiave crescente, quello che serve all'entita'.
 * Le esecuzioni concorrenti le impedisce lo scheduler (lock per job), non questa classe.
 */
public final class SyncState {

    public record State(String scope, String entity, String watermark, String status, String message,
                        Instant startedAt, Instant finishedAt, Long durationMs, Long items) {}

    private final Db db;
    private final Clock clock;

    public SyncState(Db db) {
        this(db, Clock.systemUTC());
    }

    public SyncState(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Inizia un giro: crea lo stato se non esiste e lo marca RUNNING. */
    public Run begin(String scope, String entity) {
        Instant now = clock.instant();
        String watermark = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO eone_connect.sync_state(scope, entity, status, started_at)
                    VALUES (?, ?, 'RUNNING', ?)
                    ON CONFLICT (scope, entity) DO UPDATE SET status = 'RUNNING', started_at = EXCLUDED.started_at
                    RETURNING watermark""")) {
                ps.setString(1, scope);
                ps.setString(2, entity);
                ps.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        return new Run(scope, entity, watermark, now);
    }

    public Optional<State> get(String scope, String entity) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT scope, entity, watermark, status, message, started_at, finished_at, duration_ms, items
                      FROM eone_connect.sync_state WHERE scope = ? AND entity = ?""")) {
                ps.setString(1, scope);
                ps.setString(2, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.<State>empty();
                    Timestamp s = rs.getTimestamp("started_at"), f = rs.getTimestamp("finished_at");
                    long d = rs.getLong("duration_ms");
                    Long dur = rs.wasNull() ? null : d;
                    long i = rs.getLong("items");
                    Long items = rs.wasNull() ? null : i;
                    return Optional.of(new State(rs.getString("scope"), rs.getString("entity"), rs.getString("watermark"),
                            rs.getString("status"), rs.getString("message"), s == null ? null : s.toInstant(),
                            f == null ? null : f.toInstant(), dur, items));
                }
            }
        });
    }

    /** Riparti da capo al prossimo giro (sincronizzazione completa). */
    public void reset(String scope, String entity) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eone_connect.sync_state SET watermark = NULL, status = 'NEW' WHERE scope = ? AND entity = ?")) {
                ps.setString(1, scope);
                ps.setString(2, entity);
                return ps.executeUpdate();
            }
        });
    }

    public final class Run {
        private final String scope, entity, watermark;
        private final Instant started;

        private Run(String scope, String entity, String watermark, Instant started) {
            this.scope = scope;
            this.entity = entity;
            this.watermark = watermark;
            this.started = started;
        }

        /** Punto di ripartenza (null = mai sincronizzato). */
        public String watermark() { return watermark; }

        /** Giro riuscito: il watermark avanza. */
        public void ok(String newWatermark, long items) {
            finish("OK", newWatermark, null, items);
        }

        /** Giro fallito: il watermark resta dov'era, il prossimo giro riprende da li'. */
        public void error(String message) {
            finish("ERROR", watermark, message, null);
        }

        private void finish(String status, String newWatermark, String message, Long items) {
            Instant end = clock.instant();
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE eone_connect.sync_state SET status = ?, watermark = ?, message = ?, finished_at = ?,
                               duration_ms = ?, items = ? WHERE scope = ? AND entity = ?""")) {
                    ps.setString(1, status);
                    ps.setString(2, newWatermark);
                    ps.setString(3, message);
                    ps.setTimestamp(4, Timestamp.from(end));
                    ps.setLong(5, Duration.between(started, end).toMillis());
                    if (items == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, items);
                    ps.setString(7, scope);
                    ps.setString(8, entity);
                    return ps.executeUpdate();
                }
            });
        }
    }
}
