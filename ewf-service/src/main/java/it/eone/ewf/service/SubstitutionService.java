package it.eone.ewf.service;

import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.service.ServiceException.Code;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Sostituzioni gestite dall'utente stesso ("chi mi sostituisce", "chi sto sostituendo").
 *
 * Regole:
 *  - le gestisce solo chi ha il permesso can_substitute (sull'utente; se non indicato, quello della sua classe);
 *  - il sostituto e' un utente attivo diverso da se';
 *  - il periodo va da oggi in avanti e non si sovrappone ad altre sostituzioni dello stesso utente;
 *  - togliere una sostituzione futura la cancella; una in corso la chiude a ieri (resta lo storico).
 * Il motore usa la tabella ewf_org.substitution cosi' com'e': nessuna modifica al calcolo dei sostituti.
 */
public final class SubstitutionService {

    /** Una sostituzione: userId e' sostituito da substituteId dal..al (date incluse). */
    public record Substitution(long id, long userId, String userName, long substituteId, String substituteName,
                               LocalDate from, LocalDate to, boolean activeToday) {}

    /** Utente selezionabile come sostituto. */
    public record Candidate(long id, String login, String name) {}

    private final JdbcTransactions db;
    private final Clock clock;
    private final ZoneId zone;

    public SubstitutionService(JdbcTransactions db, Clock clock, ZoneId zone) {
        this.db = db;
        this.clock = clock;
        this.zone = zone;
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    /** L'utente puo' gestire le proprie sostituzioni (can_substitute dell'utente o, se vuoto, della classe). */
    public boolean canManage(long userId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT coalesce(u.can_substitute, uc.can_substitute, false)
                      FROM ewf_org.app_user u
                      LEFT JOIN ewf_org.user_class uc ON uc.code = u.user_class_code
                     WHERE u.id = ?""")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /** Chi sostituisce l'utente: sostituzioni in corso e future. */
    public List<Substitution> mine(long userId) {
        return list("s.user_id = ?", userId);
    }

    /** Chi l'utente sta sostituendo o sostituira'. */
    public List<Substitution> asSubstitute(long userId) {
        return list("s.substitute_id = ?", userId);
    }

    private List<Substitution> list(String where, long userId) {
        LocalDate today = today();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT s.id, s.user_id, u.display_name, s.substitute_id, x.display_name, s.valid_from, s.valid_to
                      FROM ewf_org.substitution s
                      JOIN ewf_org.app_user u ON u.id = s.user_id
                      JOIN ewf_org.app_user x ON x.id = s.substitute_id
                     WHERE """ + " " + where + " AND s.valid_to >= ? ORDER BY s.valid_from, s.id")) {
                ps.setLong(1, userId);
                ps.setDate(2, Date.valueOf(today));
                List<Substitution> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate from = rs.getDate(6).toLocalDate(), to = rs.getDate(7).toLocalDate();
                        out.add(new Substitution(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                                rs.getString(5), from, to, !from.isAfter(today) && !to.isBefore(today)));
                    }
                }
                return out;
            }
        });
    }

    /** Utenti attivi selezionabili come sostituto (tutti tranne l'utente stesso e quelli nascosti). */
    public List<Candidate> candidates(long userId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, login, display_name FROM ewf_org.app_user
                     WHERE active AND NOT hidden_in_lists AND id <> ?
                     ORDER BY display_name, login""")) {
                ps.setLong(1, userId);
                List<Candidate> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Candidate(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
                return out;
            }
        });
    }

    /** L'utente indica chi lo sostituisce dal..al. */
    public Substitution add(long userId, long substituteId, LocalDate from, LocalDate to) {
        if (!canManage(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Non sei abilitato a gestire le sostituzioni");
        if (from == null || to == null) throw new ServiceException(Code.INVALID, "Indicare il periodo (dal, al)");
        if (to.isBefore(from)) throw new ServiceException(Code.INVALID, "La data finale precede quella iniziale");
        if (from.isBefore(today())) throw new ServiceException(Code.INVALID, "Il periodo non puo' iniziare nel passato");
        if (substituteId == userId) throw new ServiceException(Code.INVALID, "Non puoi sostituire te stesso");
        return db.inTransaction(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM ewf_org.app_user WHERE id = ? AND active")) {
                ps.setLong(1, substituteId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.INVALID, "Il sostituto non e' un utente attivo");
                }
            }
            // stesso utente: un solo sostituto per giorno (blocca le righe dell'utente per evitare inserimenti doppi)
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT valid_from, valid_to FROM ewf_org.substitution
                     WHERE user_id = ? AND valid_from <= ? AND valid_to >= ? FOR UPDATE""")) {
                ps.setLong(1, userId);
                ps.setDate(2, Date.valueOf(to));
                ps.setDate(3, Date.valueOf(from));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next())
                        throw ServiceException.of(Code.INVALID, "Hai gia' un sostituto dal %s al %s: toglilo o scegli un altro periodo",
                                rs.getDate(1).toLocalDate(), rs.getDate(2).toLocalDate());
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_org.substitution (user_id, substitute_id, valid_from, valid_to, changed_by, changed_at)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setLong(1, userId);
                ps.setLong(2, substituteId);
                ps.setDate(3, Date.valueOf(from));
                ps.setDate(4, Date.valueOf(to));
                ps.setLong(5, userId);
                ps.setTimestamp(6, Timestamp.from(clock.instant()));
                long id;
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
                return mine(userId).stream().filter(s -> s.id() == id).findFirst().orElseThrow();
            }
        }));
    }

    /** Toglie una propria sostituzione: futura = cancellata; in corso = chiusa a ieri (o cancellata se inizia oggi). */
    public void remove(long userId, long substitutionId) {
        if (!canManage(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Non sei abilitato a gestire le sostituzioni");
        LocalDate today = today();
        db.inTransaction(() -> db.with(c -> {
            LocalDate from, to;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT valid_from, valid_to FROM ewf_org.substitution WHERE id = ? AND user_id = ? FOR UPDATE")) {
                ps.setLong(1, substitutionId);
                ps.setLong(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Sostituzione non trovata");
                    from = rs.getDate(1).toLocalDate();
                    to = rs.getDate(2).toLocalDate();
                }
            }
            if (to.isBefore(today)) throw new ServiceException(Code.INVALID_STATE, "La sostituzione e' gia' terminata");
            if (!from.isBefore(today)) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ewf_org.substitution WHERE id = ?")) {
                    ps.setLong(1, substitutionId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ewf_org.substitution SET valid_to = ?, changed_by = ?, changed_at = ? WHERE id = ?")) {
                    ps.setDate(1, Date.valueOf(today.minusDays(1)));
                    ps.setLong(2, userId);
                    ps.setTimestamp(3, Timestamp.from(clock.instant()));
                    ps.setLong(4, substitutionId);
                    ps.executeUpdate();
                }
            }
            return null;
        }));
    }
}
