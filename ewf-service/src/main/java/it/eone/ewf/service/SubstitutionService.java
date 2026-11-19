package it.eone.ewf.service;

import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.service.ServiceException.Code;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        return insert(userId, substituteId, from, to, userId);
    }

    /** Sostituzione indicata da un amministratore per conto di userId (il controllo dell'amministratore e' di AdminService). */
    Substitution addFor(long adminId, long userId, long substituteId, LocalDate from, LocalDate to) {
        return insert(userId, substituteId, from, to, adminId);
    }

    private Substitution insert(long userId, long substituteId, LocalDate from, LocalDate to, long changedBy) {
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
                        throw ServiceException.of(Code.INVALID, "C'e' gia' un sostituto dal %s al %s: toglilo o scegli un altro periodo",
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
                ps.setLong(5, changedBy);
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
        removeInternal(userId, substitutionId, userId);
    }

    /** Un amministratore toglie la sostituzione di chiunque (il controllo e' di AdminService). */
    void removeFor(long adminId, long substitutionId) {
        removeInternal(null, substitutionId, adminId);
    }

    /** Tutte le sostituzioni in corso e future, di tutti gli utenti (per l'amministratore). */
    List<Substitution> all() {
        return list("? >= 0", 0L);   // nessun filtro sull'utente
    }

    private void removeInternal(Long ownerId, long substitutionId, long changedBy) {
        LocalDate today = today();
        db.inTransaction(() -> db.with(c -> {
            LocalDate from, to;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT valid_from, valid_to FROM ewf_org.substitution WHERE id = ? AND (? IS NULL OR user_id = ?) FOR UPDATE")) {
                ps.setLong(1, substitutionId);
                ps.setObject(2, ownerId, java.sql.Types.BIGINT);
                ps.setObject(3, ownerId, java.sql.Types.BIGINT);
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
                    ps.setLong(2, changedBy);
                    ps.setTimestamp(3, Timestamp.from(clock.instant()));
                    ps.setLong(4, substitutionId);
                    ps.executeUpdate();
                }
            }
            return null;
        }));
    }

    // ------------------------------------------------------------------ auto-sostituzione (come nel ticketing)

    /** Nome ed e-mail di un utente, per avvisare il sostituto. */
    public record Contact(String name, String email) {}

    public Optional<Contact> contact(long userId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT display_name, email FROM ewf_org.app_user WHERE id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Contact(rs.getString(1), rs.getString(2))) : Optional.empty();
                }
            }
        });
    }

    /**
     * Auto-sostituzione, come nel ticketing: l'utente ha UN sostituto per un periodo. Salvare sostituisce quello
     * indicato prima (le sostituzioni future si cancellano, quella in corso si chiude a ieri e resta nello storico).
     * Se ce n'era una in corso, un inizio nel passato diventa "da oggi"; altrimenti il periodo parte da oggi in poi.
     */
    public Substitution setMine(long userId, long substituteId, LocalDate from, LocalDate to) {
        if (!canManage(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Non sei abilitato a gestire le sostituzioni");
        if (from == null || to == null) throw new ServiceException(Code.INVALID, "Indicare il periodo (dal, al)");
        if (to.isBefore(from)) throw new ServiceException(Code.INVALID, "La data finale precede quella iniziale");
        if (substituteId == userId) throw new ServiceException(Code.INVALID, "Non puoi sostituire te stesso");
        LocalDate today = today();
        boolean running = mine(userId).stream().anyMatch(Substitution::activeToday);
        LocalDate start = from;
        if (start.isBefore(today)) {
            if (!running) throw new ServiceException(Code.INVALID, "Il periodo non puo' iniziare nel passato");
            start = today;
        }
        if (to.isBefore(today)) throw new ServiceException(Code.INVALID, "Il periodo e' gia' terminato");
        LocalDate validFrom = start;
        long id = db.inTransaction(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ewf_org.app_user WHERE id = ? AND active")) {
                ps.setLong(1, substituteId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.INVALID, "Il sostituto non e' un utente attivo");
                }
            }
            endCurrent(c, userId, today);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_org.substitution (user_id, substitute_id, valid_from, valid_to, changed_by, changed_at)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setLong(1, userId);
                ps.setLong(2, substituteId);
                ps.setDate(3, Date.valueOf(validFrom));
                ps.setDate(4, Date.valueOf(to));
                ps.setLong(5, userId);
                ps.setTimestamp(6, Timestamp.from(clock.instant()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }));
        return mine(userId).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    /** "Rimuovi sostituzione": nessun sostituto da oggi in poi (quella in corso resta nello storico fino a ieri). */
    public void clearMine(long userId) {
        if (!canManage(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Non sei abilitato a gestire le sostituzioni");
        LocalDate today = today();
        db.inTransaction(() -> db.with(c -> {
            endCurrent(c, userId, today);
            return null;
        }));
    }

    /** Chiude a ieri la sostituzione in corso e cancella quelle che iniziano da oggi in poi. */
    private void endCurrent(Connection c, long userId, LocalDate today) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement(
                "SELECT id FROM ewf_org.substitution WHERE user_id = ? AND valid_to >= ? FOR UPDATE")) {
            lock.setLong(1, userId);
            lock.setDate(2, Date.valueOf(today));
            lock.executeQuery().close();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM ewf_org.substitution WHERE user_id = ? AND valid_from >= ?")) {
            ps.setLong(1, userId);
            ps.setDate(2, Date.valueOf(today));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE ewf_org.substitution SET valid_to = ?, changed_by = ?, changed_at = ?
                 WHERE user_id = ? AND valid_from < ? AND valid_to >= ?""")) {
            ps.setDate(1, Date.valueOf(today.minusDays(1)));
            ps.setLong(2, userId);
            ps.setTimestamp(3, Timestamp.from(clock.instant()));
            ps.setLong(4, userId);
            ps.setDate(5, Date.valueOf(today));
            ps.setDate(6, Date.valueOf(today));
            ps.executeUpdate();
        }
    }
}
