package it.eone.ewf.service;

import it.eone.ewf.engine.EngineException;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.service.ServiceException.Code;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Funzioni di amministrazione. E' amministratore chi fa parte (oggi) del team di riserva del motore
 * (EngineConfig.fallbackTeamCode, default WFADM): lo stesso team che riceve i task senza assegnatario.
 *
 * Processi in errore (stato ERROR), due casi:
 *  - END_FAILED: l'azione di fine processo (es. rilascio dell'OdA) e' fallita -> "Riprova";
 *  - UNKNOWN_EXECUTION: una chiamata al sistema di origine ha avuto esito incerto -> l'amministratore verifica
 *    nel sistema di origine e conferma "e' avvenuta" (il processo avanza) o "non e' avvenuta" (il task si riapre).
 */
public final class AdminService {

    public enum ErrorKind { END_FAILED, UNKNOWN, OTHER }

    /** Un processo in errore, con quello che serve per decidere. */
    public record ProcessError(long processId, String processType, String processText, String docType, String documentKey,
                               String starter, ErrorKind kind, String reason, Instant since,
                               Long executionId, String handler, String event, String taskText, String message) {}

    private final WorkflowEngine engine;
    private final JdbcTransactions db;
    private final String adminTeam;
    private final Clock clock;
    private final ZoneId zone;

    public AdminService(WorkflowEngine engine, JdbcTransactions db, String adminTeam, Clock clock, ZoneId zone) {
        this.engine = engine;
        this.db = db;
        this.adminTeam = adminTeam;
        this.clock = clock;
        this.zone = zone;
    }

    public String adminTeam() { return adminTeam; }

    /** Membro oggi del team di amministrazione. */
    public boolean isAdmin(long userId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT 1 FROM ewf_org.team_member tm
                      JOIN ewf_org.app_user u ON u.id = tm.user_id AND u.active
                     WHERE tm.team_code = ? AND tm.user_id = ?
                       AND (tm.valid_from IS NULL OR tm.valid_from <= ?)
                       AND (tm.valid_to   IS NULL OR tm.valid_to   >= ?)""")) {
                ps.setString(1, adminTeam);
                ps.setLong(2, userId);
                ps.setDate(3, java.sql.Date.valueOf(today));
                ps.setDate(4, java.sql.Date.valueOf(today));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private void requireAdmin(long userId) {
        if (!isAdmin(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Funzione riservata agli amministratori");
    }

    /** Processi in errore, dal piu' recente. */
    public List<ProcessError> processesInError(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, p.process_type_code, pt.description, p.doc_type, p.document_key, su.display_name,
                           p.status_reason,
                           (SELECT max(coalesce(h.ended_at, h.started_at)) FROM ewf_core.handler_execution h
                             WHERE h.process_id = p.id) AS since,
                           e.id, e.handler_code, e.event, d.text, e.result_message
                      FROM ewf_core.process_instance p
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                      LEFT JOIN ewf_org.app_user su ON su.id = p.started_by
                      LEFT JOIN LATERAL (
                            SELECT x.* FROM ewf_core.handler_execution x
                             WHERE x.process_id = p.id AND x.status = 'UNKNOWN'
                             ORDER BY x.id DESC LIMIT 1) e ON true
                      LEFT JOIN ewf_core.task_instance t ON t.id = e.task_id
                      LEFT JOIN ewf_core.task_def d ON d.id = t.task_def_id
                     WHERE p.status = 'ERROR'
                     ORDER BY since DESC NULLS LAST, p.id DESC""")) {
                List<ProcessError> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String reason = rs.getString(7);
                        Long execId = rs.getObject(9) == null ? null : rs.getLong(9);
                        ErrorKind kind = reason != null && reason.startsWith(WorkflowEngine.REASON_END_FAILED) ? ErrorKind.END_FAILED
                                : execId != null ? ErrorKind.UNKNOWN : ErrorKind.OTHER;
                        Timestamp since = rs.getTimestamp(8);
                        out.add(new ProcessError(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), kind, reason, since == null ? null : since.toInstant(),
                                execId, rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)));
                    }
                }
                return out;
            }
        });
    }

    /** Riprova l'azione di fine processo (caso END_FAILED). Restituisce il nuovo stato del processo. */
    public String retryCompletion(long adminId, long processId) {
        requireAdmin(adminId);
        try {
            return engine.retryCompletion(processId, adminId).status.name();
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }

    /**
     * Conferma l'esito di una chiamata incerta (caso UNKNOWN) dopo averlo verificato nel sistema di origine.
     * La nota e' obbligatoria: resta nello storico della chiamata e nell'audit.
     */
    public Requests.Outcome resolveUnknown(long adminId, long executionId, boolean succeeded, String note) {
        requireAdmin(adminId);
        if (note == null || note.isBlank())
            throw new ServiceException(Code.INVALID, "Indicare cosa e' stato verificato nel sistema di origine");
        try {
            return engine.resolveUnknown(executionId, succeeded, adminId, note.trim());
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }

    // =====================================================================
    // Sostituzione generalizzata
    // =====================================================================

    /** Utente attivo, per le scelte nelle maschere di amministrazione. */
    public record UserRef(long id, String login, String name) {}

    private SubstitutionService substitutions() { return new SubstitutionService(db, clock, zone); }

    public List<SubstitutionService.Substitution> allSubstitutions(long adminId) {
        requireAdmin(adminId);
        return substitutions().all();
    }

    public SubstitutionService.Substitution addSubstitution(long adminId, long userId, long substituteId,
                                                           LocalDate from, LocalDate to) {
        requireAdmin(adminId);
        return substitutions().addFor(adminId, userId, substituteId, from, to);
    }

    public void removeSubstitution(long adminId, long substitutionId) {
        requireAdmin(adminId);
        substitutions().removeFor(adminId, substitutionId);
    }

    public List<UserRef> activeUsers(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, login, display_name FROM ewf_org.app_user WHERE active ORDER BY display_name, login")) {
                List<UserRef> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
                return out;
            }
        });
    }

    // =====================================================================
    // Avvio forzato
    // =====================================================================

    /** Tipo di processo avviabile: non sospeso e con una versione attiva. */
    public record StartableType(String code, String description, String docType, boolean manualStart) {}

    /** Tipi documento per cui esiste almeno un processo avviabile. */
    public List<String> docTypes(long adminId) {
        requireAdmin(adminId);
        return startable(null).stream().map(StartableType::docType).distinct().sorted().toList();
    }

    /** Processi ammessi per un tipo documento (controllo di ammissibilita' dell'avvio forzato). */
    public List<StartableType> startableTypes(long adminId, String docType) {
        requireAdmin(adminId);
        return startable(docType);
    }

    private List<StartableType> startable(String docType) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT pt.code, pt.description, pt.doc_type, pt.manual_start
                      FROM ewf_core.process_type pt
                     WHERE NOT pt.suspended
                       AND EXISTS (SELECT 1 FROM ewf_core.process_version v
                                    WHERE v.process_type_code = pt.code AND v.status = 'ACTIVE')
                       AND (? IS NULL OR pt.doc_type = ?)
                     ORDER BY pt.doc_type, pt.description""")) {
                ps.setString(1, docType);
                ps.setString(2, docType);
                List<StartableType> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new StartableType(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
                }
                return out;
            }
        });
    }

    /**
     * Avvio forzato da parte dell'amministratore. Il processo deve essere ammesso per il tipo documento;
     * il motore legge il documento (se esiste l'adattatore) e rifiuta doppioni e documenti inesistenti.
     * L'audit registra l'amministratore come chi ha avviato.
     */
    public long forceStart(long adminId, String docType, String documentKey, String processType) {
        requireAdmin(adminId);
        if (docType == null || docType.isBlank() || processType == null || processType.isBlank())
            throw new ServiceException(Code.INVALID, "Indicare tipo documento e processo");
        if (documentKey == null || documentKey.isBlank())
            throw new ServiceException(Code.INVALID, "Indicare il documento");
        boolean admitted = startable(docType).stream().anyMatch(t -> t.code().equals(processType));
        if (!admitted)
            throw ServiceException.of(Code.INVALID, "Il processo %s non e' ammesso per il tipo documento %s", processType, docType);
        try {
            return engine.start(new Requests.Start(processType, documentKey.trim(), null, adminId, null)).id;
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }

    // =====================================================================
    // Gestione processi (consultazione di tutti i processi, annullamento)
    // =====================================================================

    /** Tutti i processi (al massimo 500, piu' recenti prima), filtrati per stato e testo libero. */
    public List<Views.ParticipatedProcess> processes(long adminId, Views.ParticipationFilter filter, String text) {
        requireAdmin(adminId);
        String status = switch (filter == null ? Views.ParticipationFilter.ALL : filter) {
            case RUNNING -> " AND p.status IN ('RUNNING','SUSPENDED','COMPLETING','ERROR')";
            case CLOSED -> " AND p.status NOT IN ('RUNNING','SUSPENDED','COMPLETING','ERROR')";
            case ALL -> "";
        };
        String like = text == null || text.isBlank() ? null : "%" + text.trim().toLowerCase() + "%";
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, p.process_type_code, pt.description AS pt_text, p.doc_type, p.document_key,
                           p.text AS p_text, p.net_value, p.currency, p.status, p.started_at, p.ended_at,
                           su.display_name AS starter,
                           (SELECT string_agg(td.text || coalesce(' (' || au.display_name || ')', coalesce(' (team ' || t.team_code || ')', '')),
                                              ', ' ORDER BY t.step_no)
                              FROM ewf_core.task_instance t
                              JOIN ewf_core.task_def td ON td.id = t.task_def_id
                              LEFT JOIN ewf_org.app_user au ON au.id = t.assignee_id
                             WHERE t.process_id = p.id AND t.status IN ('OPEN','EXECUTING')) AS open_tasks
                      FROM ewf_core.process_instance p
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                      LEFT JOIN ewf_org.app_user su ON su.id = p.started_by
                     WHERE (? IS NULL OR lower(p.document_key) LIKE ? OR lower(pt.description) LIKE ?
                            OR lower(coalesce(p.text, '')) LIKE ? OR lower(coalesce(su.display_name, '')) LIKE ?)"""
                    + status + " ORDER BY p.started_at DESC, p.id DESC LIMIT 500")) {
                ps.setString(1, like);
                for (int i = 2; i <= 5; i++) ps.setString(i, like);
                List<Views.ParticipatedProcess> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        java.math.BigDecimal amount = rs.getBigDecimal("net_value");
                        it.eone.ewf.engine.model.Money value = amount == null || rs.getString("currency") == null ? null
                                : new it.eone.ewf.engine.model.Money(amount, rs.getString("currency"));
                        Timestamp ended = rs.getTimestamp("ended_at");
                        out.add(new Views.ParticipatedProcess(rs.getLong("id"), rs.getString("process_type_code"),
                                rs.getString("pt_text"), rs.getString("doc_type"), rs.getString("document_key"),
                                rs.getString("p_text"), value, rs.getString("status"),
                                rs.getTimestamp("started_at").toInstant(), ended == null ? null : ended.toInstant(),
                                rs.getString("starter"), null, rs.getString("open_tasks")));
                    }
                }
                return out;
            }
        });
    }

    /**
     * Annulla un processo in corso (nota obbligatoria, finisce nell'audit). Attenzione: nel sistema di origine
     * il documento resta com'e' (es. OdA ancora bloccato): va gestito li'.
     */
    public void cancel(long adminId, long processId, String note) {
        requireAdmin(adminId);
        if (note == null || note.isBlank()) throw new ServiceException(Code.INVALID, "Scrivere il motivo dell'annullamento");
        try {
            engine.cancel(processId, adminId, note.trim());
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }

    // =====================================================================
    // Gestione utenti
    // =====================================================================

    /** Utente come lo vede l'amministratore. canSubstitute null = vale la classe utente. */
    public record UserRow(long id, String login, String displayName, String email, String sapUser, String userClass,
                          Boolean canSubstitute, boolean active, boolean locked, String teams) {}

    /** Dati modificabili; id null = nuovo utente. */
    public record UserEdit(Long id, String login, String displayName, String email, String sapUser, String userClass,
                           Boolean canSubstitute, boolean active) {}

    public record UserClass(String code, String description) {}

    public List<UserRow> users(long adminId) {
        requireAdmin(adminId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT u.id, u.login, u.display_name, u.email, u.sap_user, u.user_class_code, u.can_substitute,
                           u.active, (u.locked_until IS NOT NULL AND u.locked_until > now()) AS locked,
                           (SELECT string_agg(tm.team_code, ', ' ORDER BY tm.team_code) FROM ewf_org.team_member tm
                             WHERE tm.user_id = u.id AND (tm.valid_from IS NULL OR tm.valid_from <= ?)
                               AND (tm.valid_to IS NULL OR tm.valid_to >= ?)) AS teams
                      FROM ewf_org.app_user u
                     ORDER BY u.active DESC, u.display_name, u.login""")) {
                ps.setObject(1, today);
                ps.setObject(2, today);
                List<UserRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean cs = rs.getBoolean("can_substitute");
                        out.add(new UserRow(rs.getLong("id"), rs.getString("login"), rs.getString("display_name"),
                                rs.getString("email"), rs.getString("sap_user"), rs.getString("user_class_code"),
                                rs.wasNull() ? null : cs, rs.getBoolean("active"), rs.getBoolean("locked"),
                                rs.getString("teams")));
                    }
                }
                return out;
            }
        });
    }

    public List<UserClass> userClasses(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code, description FROM ewf_org.user_class ORDER BY code")) {
                List<UserClass> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new UserClass(rs.getString(1), rs.getString(2)));
                }
                return out;
            }
        });
    }

    /** Crea o aggiorna un utente. Login: minuscolo, unico, non modificabile dopo la creazione. */
    public long saveUser(long adminId, UserEdit u) {
        requireAdmin(adminId);
        String name = u.displayName() == null ? "" : u.displayName().trim();
        if (name.isEmpty()) throw new ServiceException(Code.INVALID, "Indicare il nome");
        String email = u.email() == null || u.email().isBlank() ? null : u.email().trim();
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            throw new ServiceException(Code.INVALID, "Indirizzo e-mail non valido");
        String sap = u.sapUser() == null || u.sapUser().isBlank() ? null : u.sapUser().trim().toUpperCase();
        if (sap != null && sap.length() > 12) throw new ServiceException(Code.INVALID, "Utente SAP: al massimo 12 caratteri");
        String cls = u.userClass() == null || u.userClass().isBlank() ? null : u.userClass();
        if (u.id() != null && u.id() == adminId && !u.active())
            throw new ServiceException(Code.INVALID, "Non puoi disattivare te stesso");
        return db.inTransaction(() -> db.with(c -> {
            if (u.id() == null) {
                String login = u.login() == null ? "" : u.login().trim().toLowerCase();
                if (!login.matches("[a-z0-9._-]{2,64}"))
                    throw new ServiceException(Code.INVALID, "Login: da 2 a 64 caratteri tra lettere, cifre, punto, trattino");
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ewf_org.app_user WHERE lower(login) = ?")) {
                    ps.setString(1, login);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw ServiceException.of(Code.INVALID, "Il login %s esiste gia'", login);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO ewf_org.app_user (login, display_name, email, sap_user, user_class_code, can_substitute,
                                                      active, password_must_change)
                        VALUES (?, ?, ?, ?, ?, ?, true, true) RETURNING id""")) {
                    ps.setString(1, login);
                    ps.setString(2, name);
                    ps.setString(3, email);
                    ps.setString(4, sap);
                    ps.setString(5, cls);
                    ps.setObject(6, u.canSubstitute(), java.sql.Types.BOOLEAN);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_org.app_user SET display_name = ?, email = ?, sap_user = ?, user_class_code = ?,
                           can_substitute = ?, active = ?, updated_at = now()
                     WHERE id = ?""")) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, sap);
                ps.setString(4, cls);
                ps.setObject(5, u.canSubstitute(), java.sql.Types.BOOLEAN);
                ps.setBoolean(6, u.active());
                ps.setLong(7, u.id());
                if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Utente non trovato");
                return u.id();
            }
        }));
    }

    /** Password provvisoria (da cambiare al primo accesso); toglie anche il blocco. La si mostra una volta sola. */
    public String resetPassword(long adminId, long userId) {
        requireAdmin(adminId);
        return new it.eone.ewf.service.auth.AuthService(db, new it.eone.ewf.service.auth.PasswordHasher(), clock)
                .resetPassword(userId);
    }

    /** Toglie il blocco dopo troppi tentativi sbagliati, senza cambiare la password. */
    public void unlock(long adminId, long userId) {
        requireAdmin(adminId);
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ewf_org.app_user SET failed_logins = 0, locked_until = NULL, updated_at = now() WHERE id = ?")) {
                ps.setLong(1, userId);
                if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Utente non trovato");
                return null;
            }
        });
    }

    // =====================================================================
    // Team
    // =====================================================================

    /** Team con il numero di membri attivi oggi. */
    public record TeamRow(String code, String description, int activeMembers) {}

    /** Appartenenza a un team; from/to null = senza limite. */
    public record Membership(String teamCode, String teamDescription, long userId, String login, String userName,
                             LocalDate from, LocalDate to, boolean activeToday) {}

    private LocalDate today() { return LocalDate.now(clock.withZone(zone)); }

    public List<TeamRow> teams(long adminId) {
        requireAdmin(adminId);
        LocalDate today = today();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.code, t.description,
                           (SELECT count(*) FROM ewf_org.team_member m JOIN ewf_org.app_user u ON u.id = m.user_id AND u.active
                             WHERE m.team_code = t.code AND (m.valid_from IS NULL OR m.valid_from <= ?)
                               AND (m.valid_to IS NULL OR m.valid_to >= ?)) AS n
                      FROM ewf_org.team t ORDER BY t.code""")) {
                ps.setObject(1, today);
                ps.setObject(2, today);
                List<TeamRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TeamRow(rs.getString(1), rs.getString(2), rs.getInt(3)));
                }
                return out;
            }
        });
    }

    /** Crea (isNew) o rinomina un team. Codice: maiuscolo, lettere/cifre/_ , non modificabile dopo. */
    public void saveTeam(long adminId, String code, String description, boolean isNew) {
        requireAdmin(adminId);
        String c0 = code == null ? "" : code.trim().toUpperCase();
        String d = description == null ? "" : description.trim();
        if (d.isEmpty()) throw new ServiceException(Code.INVALID, "Indicare la descrizione del team");
        if (d.length() > 80) throw new ServiceException(Code.INVALID, "Descrizione: al massimo 80 caratteri");
        db.with(c -> {
            if (isNew) {
                if (!c0.matches("[A-Z0-9_]{2,20}"))
                    throw new ServiceException(Code.INVALID, "Codice team: da 2 a 20 caratteri tra lettere, cifre e _");
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ewf_org.team (code, description) VALUES (?, ?) ON CONFLICT (code) DO NOTHING")) {
                    ps.setString(1, c0);
                    ps.setString(2, d);
                    if (ps.executeUpdate() == 0) throw ServiceException.of(Code.INVALID, "Il team %s esiste gia'", c0);
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("UPDATE ewf_org.team SET description = ? WHERE code = ?")) {
                    ps.setString(1, d);
                    ps.setString(2, c0);
                    if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Team non trovato");
                }
            }
            return null;
        });
    }

    /** Membri di un team (anche passati e futuri), attivi prima. */
    public List<Membership> members(long adminId, String teamCode) {
        requireAdmin(adminId);
        return memberships("m.team_code = ?", teamCode);
    }

    /** Team di un utente (anche passati e futuri). */
    public List<Membership> teamsOfUser(long adminId, long userId) {
        requireAdmin(adminId);
        return memberships("m.user_id = ?", userId);
    }

    private List<Membership> memberships(String where, Object param) {
        LocalDate today = today();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT m.team_code, t.description, m.user_id, u.login, u.display_name, m.valid_from, m.valid_to
                      FROM ewf_org.team_member m
                      JOIN ewf_org.team t ON t.code = m.team_code
                      JOIN ewf_org.app_user u ON u.id = m.user_id
                     WHERE """ + " " + where + " ORDER BY u.display_name, m.team_code")) {
                ps.setObject(1, param);
                List<Membership> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        java.sql.Date f = rs.getDate(6), t = rs.getDate(7);
                        LocalDate from = f == null ? null : f.toLocalDate(), to = t == null ? null : t.toLocalDate();
                        boolean active = (from == null || !from.isAfter(today)) && (to == null || !to.isBefore(today));
                        out.add(new Membership(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                                rs.getString(5), from, to, active));
                    }
                }
                out.sort((a, b) -> Boolean.compare(b.activeToday(), a.activeToday()));
                return out;
            }
        });
    }

    /**
     * Mette un utente in un team per un periodo (from null = da sempre, to null = senza scadenza). Un utente ha
     * una sola appartenenza per team: se c'era gia', il periodo viene sostituito.
     */
    public void setMember(long adminId, String teamCode, long userId, LocalDate from, LocalDate to) {
        requireAdmin(adminId);
        if (from != null && to != null && to.isBefore(from))
            throw new ServiceException(Code.INVALID, "La data finale precede quella iniziale");
        if (adminTeam.equals(teamCode) && userId == adminId && to != null && to.isBefore(today()))
            throw new ServiceException(Code.INVALID, "Non puoi toglierti da solo dagli amministratori");
        db.inTransaction(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_org.team_member (team_code, user_id, valid_from, valid_to) VALUES (?, ?, ?, ?)
                    ON CONFLICT (team_code, user_id) DO UPDATE SET valid_from = EXCLUDED.valid_from, valid_to = EXCLUDED.valid_to""")) {
                ps.setString(1, teamCode);
                ps.setLong(2, userId);
                ps.setObject(3, from);
                ps.setObject(4, to);
                ps.executeUpdate();
            }
            requireAdminsLeft(c);
            return null;
        }));
    }

    /**
     * Toglie un utente da un team da oggi: l'appartenenza si chiude a ieri (se era gia' iniziata) o si cancella
     * (se doveva ancora iniziare). I task gia' arrivati al team restano al team.
     */
    public void endMember(long adminId, String teamCode, long userId) {
        requireAdmin(adminId);
        if (adminTeam.equals(teamCode) && userId == adminId)
            throw new ServiceException(Code.INVALID, "Non puoi toglierti da solo dagli amministratori");
        LocalDate today = today();
        db.inTransaction(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM ewf_org.team_member WHERE team_code = ? AND user_id = ? AND valid_from >= ?""")) {
                ps.setString(1, teamCode);
                ps.setLong(2, userId);
                ps.setObject(3, today);
                if (ps.executeUpdate() > 0) {
                    requireAdminsLeft(c);
                    return null;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_org.team_member SET valid_to = ?
                     WHERE team_code = ? AND user_id = ? AND (valid_to IS NULL OR valid_to >= ?)""")) {
                ps.setObject(1, today.minusDays(1));
                ps.setString(2, teamCode);
                ps.setLong(3, userId);
                ps.setObject(4, today);
                ps.executeUpdate();
            }
            requireAdminsLeft(c);
            return null;
        }));
    }

    /** Dopo una modifica deve restare almeno un amministratore attivo, altrimenti nessuno potrebbe rimediare. */
    private void requireAdminsLeft(java.sql.Connection c) throws java.sql.SQLException {
        LocalDate today = today();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM ewf_org.team_member m JOIN ewf_org.app_user u ON u.id = m.user_id AND u.active
                 WHERE m.team_code = ? AND (m.valid_from IS NULL OR m.valid_from <= ?)
                   AND (m.valid_to IS NULL OR m.valid_to >= ?) LIMIT 1""")) {
            ps.setString(1, adminTeam);
            ps.setObject(2, today);
            ps.setObject(3, today);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    throw ServiceException.of(Code.INVALID, "Deve restare almeno un amministratore attivo (team %s)", adminTeam);
            }
        }
    }
}
