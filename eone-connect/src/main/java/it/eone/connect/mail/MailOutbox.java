package it.eone.connect.mail;

import it.eone.connect.db.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Coda di uscita delle mail (eone_connect.mail_outbox).
 *
 * Accodamento: enqueue(conn, mail) dentro la transazione di chi genera la mail (se quella va in rollback, la mail
 * non esiste), oppure enqueue(mail) in una transazione propria. Con una dedupKey gia' presente non succede niente:
 * ripetere un accodamento e' sicuro.
 *
 * Spedizione (MailSendJob): claim() prende le mail dovute e le marca SENDING; poi sent() o failed().
 * Una SENDING rimasta appesa (istanza caduta durante l'invio) torna disponibile dopo STALE: in quel raro caso
 * la mail puo' partire due volte, mai zero.
 */
public final class MailOutbox {

    /** Attese fra un tentativo e il successivo; dopo l'ultimo la mail diventa DEAD. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(4), Duration.ofHours(12));
    static final Duration STALE = Duration.ofMinutes(15);

    private final Db db;

    public MailOutbox(Db db) { this.db = db; }

    public record Pending(long id, MailMessage message, int attempts) {}

    public record Row(long id, String source, String ref, String to, String subject, String status, int attempts,
                      Instant nextTryAt, String lastError, Instant createdAt, Instant sentAt) {}

    /** Nella transazione del chiamante. Restituisce l'id, oppure vuoto se la dedupKey c'era gia'. */
    public static Optional<Long> enqueue(Connection c, MailMessage m) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO eone_connect.mail_outbox(source, ref, dedup_key, to_addr, cc_addr, subject, body_text, body_html)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (dedup_key) DO NOTHING
                RETURNING id""")) {
            ps.setString(1, m.source());
            ps.setString(2, m.ref());
            ps.setString(3, m.dedupKey());
            ps.setString(4, String.join(",", m.to()));
            ps.setString(5, m.cc().isEmpty() ? null : String.join(",", m.cc()));
            ps.setString(6, m.subject().length() > 255 ? m.subject().substring(0, 252) + "..." : m.subject());
            ps.setString(7, m.bodyText());
            ps.setString(8, m.bodyHtml());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public Optional<Long> enqueue(MailMessage m) {
        return db.tx(c -> enqueue(c, m));
    }

    /** Prende fino a max mail da spedire e le marca SENDING (piu' istanze insieme non prendono la stessa). */
    public List<Pending> claim(int max, Instant now) {
        return db.tx(c -> {
            List<Pending> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE eone_connect.mail_outbox SET status = 'SENDING', claimed_at = ?
                     WHERE id IN (SELECT id FROM eone_connect.mail_outbox
                                   WHERE (status IN ('NEW','ERROR') AND next_try_at <= ?)
                                      OR (status = 'SENDING' AND claimed_at < ?)
                                   ORDER BY next_try_at, id
                                   LIMIT ? FOR UPDATE SKIP LOCKED)
                    RETURNING id, source, ref, dedup_key, to_addr, cc_addr, subject, body_text, body_html, attempts""")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setTimestamp(2, Timestamp.from(now));
                ps.setTimestamp(3, Timestamp.from(now.minus(STALE)));
                ps.setInt(4, max);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String cc = rs.getString(6);
                        out.add(new Pending(rs.getLong(1), new MailMessage(rs.getString(2), rs.getString(3), rs.getString(4),
                                split(rs.getString(5)), cc == null ? List.of() : split(cc), rs.getString(7),
                                rs.getString(8), rs.getString(9)), rs.getInt(10)));
                    }
                }
            }
            out.sort(java.util.Comparator.comparingLong(Pending::id));
            return out;
        });
    }

    public void sent(long id, Instant now) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eone_connect.mail_outbox SET status = 'SENT', sent_at = ?, attempts = attempts + 1, "
                            + "last_error = NULL WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Errore: si ritenta secondo BACKOFF; permanente o tentativi esauriti -> DEAD. Restituisce il nuovo stato. */
    public String failed(long id, int attemptsBefore, MailException e, Instant now) {
        int attempt = attemptsBefore + 1;
        boolean dead = e.permanent() || attempt > BACKOFF.size();
        String status = dead ? "DEAD" : "ERROR";
        Instant next = dead ? now : now.plus(BACKOFF.get(attempt - 1));
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eone_connect.mail_outbox SET status = ?, attempts = ?, next_try_at = ?, last_error = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setInt(2, attempt);
                ps.setTimestamp(3, Timestamp.from(next));
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ps.setString(4, msg.length() > 2000 ? msg.substring(0, 2000) : msg);
                ps.setLong(5, id);
                ps.executeUpdate();
            }
            return null;
        });
        return status;
    }

    /** Rimette in coda una mail DEAD (dopo aver corretto la causa, es. la configurazione). */
    public boolean retry(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE eone_connect.mail_outbox SET status = 'NEW', attempts = 0, next_try_at = now() "
                            + "WHERE id = ? AND status IN ('DEAD','ERROR')")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Ultime mail, per cruscotto e diagnosi (senza i corpi). */
    public List<Row> recent(String source, int limit) {
        return db.tx(c -> {
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, source, ref, to_addr, subject, status, attempts, next_try_at, last_error, created_at, sent_at
                      FROM eone_connect.mail_outbox WHERE (? IS NULL OR source = ?)
                     ORDER BY id DESC LIMIT ?""")) {
                ps.setString(1, source);
                ps.setString(2, source);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getInt(7), ts(rs, 8), rs.getString(9), ts(rs, 10), ts(rs, 11)));
                }
            }
            return out;
        });
    }

    /** Cancella le mail spedite piu' vecchie di keep (le DEAD restano, finche' qualcuno non le guarda). */
    public int purgeSent(Duration keep, Instant now) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eone_connect.mail_outbox WHERE status = 'SENT' AND sent_at < ?")) {
                ps.setTimestamp(1, Timestamp.from(now.minus(keep)));
                return ps.executeUpdate();
            }
        });
    }

    private static Instant ts(ResultSet rs, int i) throws SQLException {
        Timestamp t = rs.getTimestamp(i);
        return t == null ? null : t.toInstant();
    }

    private static List<String> split(String s) {
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
