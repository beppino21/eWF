package it.eone.ewf.service;

import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.assign.RuleBasedAssignmentResolver;
import it.eone.ewf.engine.assign.RuleBasedAssignmentResolver.Evaluation;
import it.eone.ewf.engine.jdbc.JdbcAssignmentRules;
import it.eone.ewf.engine.jdbc.JdbcException;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.AssignmentResolver.Query;
import it.eone.ewf.engine.spi.CurrencyConverter;
import it.eone.ewf.service.ServiceException.Code;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ruoli e regole di assegnazione (amministratori): chi riceve i task con assegnazione "per regole".
 * <p>
 * Il motore sceglie tra le regole del ruolo quelle compatibili con il documento (tipo processo, gruppo acquisti,
 * divisione, magazzino, fascia di importo, validita'); vince quella con piu' criteri valorizzati, a parita' la
 * piu' vecchia. Il simulatore usa lo stesso codice del motore ({@link RuleBasedAssignmentResolver#explain}).
 * <p>
 * Le eccezioni di assegnazione, i gruppi intercambiabili e le autorizzazioni per processo del legacy non fanno
 * parte dello standard (decisione del 20/11/2026): si aggiungeranno per cliente, separate.
 */
public final class AssignmentService {

    /** Un ruolo: quante regole ha e in quanti task di versioni attive o in bozza e' usato. */
    public record RoleRow(String code, String description, int rules, int usedInTasks) {}

    /** Dove e' usato un ruolo. */
    public record RoleUse(String processType, int versionNo, String status, int stepNo, String option, String taskText) {}

    /** Una regola, con il nome del destinatario. */
    public record RuleRow(long id, String roleCode, String processType, String purchasingGroup, String plant,
                          String storageLocation, BigDecimal amountFrom, BigDecimal amountTo, String currency,
                          Long userId, String userName, boolean userActive, String teamCode,
                          LocalDate validFrom, LocalDate validTo, int specificity) {}

    /** Dati di una regola da salvare (id null = nuova). Campi vuoti = qualsiasi valore. */
    public record RuleEdit(Long id, String roleCode, String processType, String purchasingGroup, String plant,
                           String storageLocation, BigDecimal amountFrom, BigDecimal amountTo, String currency,
                           Long userId, String teamCode, LocalDate validFrom, LocalDate validTo) {}

    /** Il caso da simulare: come sarebbe il documento. */
    public record SimRequest(String processType, String roleCode, String purchasingGroup, String plant,
                             String storageLocation, BigDecimal amount, String currency, LocalDate date) {}

    /** Una regola nel risultato della simulazione: vince la prima compatibile. */
    public record SimLine(RuleRow rule, boolean winner, String excludedBecause) {}

    /** Esito: chi riceve il task (o il team di riserva) e il perche', regola per regola. */
    public record SimResult(String outcome, boolean assigned, List<SimLine> lines) {}

    private final JdbcTransactions db;
    private final AdminService admin;
    private final CurrencyConverter currency;
    private final String fallbackTeam;

    public AssignmentService(JdbcTransactions db, AdminService admin, CurrencyConverter currency, String fallbackTeam) {
        this.db = db;
        this.admin = admin;
        this.currency = currency;
        this.fallbackTeam = fallbackTeam;
    }

    private void requireAdmin(long userId) {
        if (!admin.isAdmin(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Funzione riservata agli amministratori");
    }

    // ------------------------------------------------------------------ ruoli

    public List<RoleRow> roles(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT r.code, r.description,
                           (SELECT count(*) FROM ewf_org.assignment_rule a WHERE a.role_code = r.code),
                           (SELECT count(*) FROM ewf_core.task_def d
                              JOIN ewf_core.step_def s ON s.id = d.step_id
                              JOIN ewf_core.process_version v ON v.id = s.version_id
                             WHERE d.role_code = r.code AND v.status IN ('ACTIVE','DRAFT'))
                      FROM ewf_org.role r ORDER BY r.code""");
                 ResultSet rs = ps.executeQuery()) {
                List<RoleRow> out = new ArrayList<>();
                while (rs.next()) out.add(new RoleRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
                return out;
            }
        });
    }

    /** Task di versioni attive o in bozza che usano il ruolo. */
    public List<RoleUse> roleUses(long adminId, String role) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT v.process_type_code, v.version_no, v.status, s.step_no, s.option_code, d.text
                      FROM ewf_core.task_def d
                      JOIN ewf_core.step_def s ON s.id = d.step_id
                      JOIN ewf_core.process_version v ON v.id = s.version_id
                     WHERE d.role_code = ? AND v.status IN ('ACTIVE','DRAFT')
                     ORDER BY v.process_type_code, v.version_no DESC, s.step_no""")) {
                ps.setString(1, role);
                List<RoleUse> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new RoleUse(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4),
                            rs.getString(5), rs.getString(6)));
                }
                return out;
            }
        });
    }

    /** Crea o rinomina (solo la descrizione) un ruolo. Il codice non cambia. */
    public void saveRole(long adminId, String code, String description, boolean isNew) {
        requireAdmin(adminId);
        String c0 = code == null ? "" : code.trim().toUpperCase();
        if (!c0.matches("[A-Z0-9_]{2,20}"))
            throw new ServiceException(Code.INVALID, "Codice del ruolo: da 2 a 20 tra lettere, cifre e _");
        if (blank(description)) throw new ServiceException(Code.INVALID, "La descrizione e' obbligatoria");
        tx(() -> db.with(c -> {
            if (isNew) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO ewf_org.role(code, description) VALUES (?, ?)")) {
                    ps.setString(1, c0);
                    ps.setString(2, description.trim());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("UPDATE ewf_org.role SET description = ? WHERE code = ?")) {
                    ps.setString(1, description.trim());
                    ps.setString(2, c0);
                    if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Ruolo inesistente");
                }
            }
            return null;
        }));
    }

    /** Elimina un ruolo senza regole e mai usato nelle definizioni (nemmeno nelle versioni ritirate). */
    public void deleteRole(long adminId, String code) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            if (count(c, "SELECT count(*) FROM ewf_org.assignment_rule WHERE role_code = ?", code) > 0)
                throw new ServiceException(Code.INVALID, "Il ruolo ha ancora delle regole: eliminarle prima");
            if (count(c, "SELECT count(*) FROM ewf_core.task_def WHERE role_code = ?", code) > 0)
                throw new ServiceException(Code.INVALID, "Il ruolo e' usato nei task dei processi (anche in versioni"
                        + " passate): non si puo' eliminare");
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ewf_org.role WHERE code = ?")) {
                ps.setString(1, code);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    // ------------------------------------------------------------------ regole

    /** Regole del ruolo, dalla piu' specifica. */
    public List<RuleRow> rules(long adminId, String role) {
        requireAdmin(adminId);
        return db.with(c -> rules(c, role));
    }

    public long saveRule(long adminId, RuleEdit e) {
        requireAdmin(adminId);
        String role = upper(e.roleCode());
        if (role == null) throw new ServiceException(Code.INVALID, "Ruolo obbligatorio");
        String pg = upper(e.purchasingGroup()), plant = upper(e.plant()), sloc = upper(e.storageLocation());
        String type = upper(e.processType()), team = upper(e.teamCode());
        if (pg != null && pg.length() > 3) throw new ServiceException(Code.INVALID, "Gruppo acquisti: al massimo 3 caratteri");
        if (plant != null && plant.length() > 4) throw new ServiceException(Code.INVALID, "Divisione: al massimo 4 caratteri");
        if (sloc != null && sloc.length() > 4) throw new ServiceException(Code.INVALID, "Magazzino: al massimo 4 caratteri");
        if (e.userId() == null && team == null)
            throw new ServiceException(Code.INVALID, "Indicare chi riceve il task: un utente, un team o entrambi");
        if (e.amountFrom() != null && e.amountTo() != null && e.amountFrom().compareTo(e.amountTo()) > 0)
            throw new ServiceException(Code.INVALID, "Importo: 'da' maggiore di 'a'");
        if (e.validFrom() != null && e.validTo() != null && e.validFrom().isAfter(e.validTo()))
            throw new ServiceException(Code.INVALID, "Validita': 'dal' successivo ad 'al'");
        boolean amounts = e.amountFrom() != null || e.amountTo() != null;
        String cur = amounts ? (blank(e.currency()) ? "EUR" : e.currency().trim().toUpperCase()) : null;
        return tx(() -> db.with(c -> {
            if (type != null && count(c, "SELECT count(*) FROM ewf_core.process_type WHERE code = ?", type) == 0)
                throw new ServiceException(Code.INVALID, "Tipo processo " + type + " inesistente");
            Long id = e.id();
            String sql = id == null ? """
                    INSERT INTO ewf_org.assignment_rule(role_code, process_type_code, purchasing_group, plant, storage_location,
                           amount_from, amount_to, currency, user_id, target_team_code, valid_from, valid_to)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""" : """
                    UPDATE ewf_org.assignment_rule SET role_code = ?, process_type_code = ?, purchasing_group = ?, plant = ?,
                           storage_location = ?, amount_from = ?, amount_to = ?, currency = ?, user_id = ?,
                           target_team_code = ?, valid_from = ?, valid_to = ?
                     WHERE id = ? RETURNING id""";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, role);
                ps.setString(2, type);
                ps.setString(3, pg);
                ps.setString(4, plant);
                ps.setString(5, sloc);
                setDecimal(ps, 6, e.amountFrom());
                setDecimal(ps, 7, e.amountTo());
                ps.setString(8, cur);
                if (e.userId() == null) ps.setNull(9, Types.BIGINT); else ps.setLong(9, e.userId());
                ps.setString(10, team);
                setDate(ps, 11, e.validFrom());
                setDate(ps, 12, e.validTo());
                if (id != null) ps.setLong(13, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Regola non trovata");
                    return rs.getLong(1);
                }
            }
        }));
    }

    public void deleteRule(long adminId, long ruleId) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ewf_org.assignment_rule WHERE id = ?")) {
                ps.setLong(1, ruleId);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    /**
     * Avvisi sulle regole del ruolo: coppie che possono valere per lo stesso documento con la stessa specificita'
     * (vince la piu' vecchia, forse senza volerlo), destinatari disattivati o in team inesistenti, regole scadute.
     */
    public List<String> warnings(long adminId, String role) {
        requireAdmin(adminId);
        List<RuleRow> rules = rules(adminId, role);
        LocalDate today = LocalDate.now(java.time.ZoneId.systemDefault());
        List<String> out = new ArrayList<>();
        for (RuleRow r : rules) {
            if (r.userId() != null && !r.userActive())
                out.add("Regola " + r.id() + ": " + r.userName() + " e' disattivato");
            if (r.validTo() != null && r.validTo().isBefore(today))
                out.add("Regola " + r.id() + ": scaduta il " + r.validTo());
        }
        for (int i = 0; i < rules.size(); i++)
            for (int j = i + 1; j < rules.size(); j++) {
                RuleRow a = rules.get(i), b = rules.get(j);
                if (a.specificity() != b.specificity() || !canOverlap(a, b)) continue;
                RuleRow older = a.id() < b.id() ? a : b, newer = older == a ? b : a;
                if (Objects.equals(a.userId(), b.userId()) && Objects.equals(a.teamCode(), b.teamCode())) continue;
                out.add("Regole " + older.id() + " e " + newer.id() + ": possono valere per lo stesso documento con la"
                        + " stessa specificita'; vince la " + older.id() + " (la piu' vecchia)");
            }
        return out;
    }

    /** Ruoli usati in versioni attive senza nessuna regola: quei task andrebbero al team di riserva. */
    public List<String> rolesWithoutRules(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT DISTINCT d.role_code FROM ewf_core.task_def d
                      JOIN ewf_core.step_def s ON s.id = d.step_id
                      JOIN ewf_core.process_version v ON v.id = s.version_id
                     WHERE v.status = 'ACTIVE' AND d.assignee_mode = 'RULES' AND d.role_code IS NOT NULL
                       AND d.team_code IS NULL
                       AND NOT EXISTS (SELECT 1 FROM ewf_org.assignment_rule a WHERE a.role_code = d.role_code)
                     ORDER BY 1""");
                 ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        });
    }

    // ------------------------------------------------------------------ simulazione

    /** Chi riceverebbe il task: stessa logica del motore, con il motivo per ogni regola esclusa. */
    public SimResult simulate(long adminId, SimRequest q) {
        requireAdmin(adminId);
        String role = upper(q.roleCode());
        if (role == null) throw new ServiceException(Code.INVALID, "Indicare il ruolo");
        Map<String, String> ctx = RuleBasedAssignmentResolver.ctx(upper(q.purchasingGroup()), upper(q.plant()),
                upper(q.storageLocation()));
        Money value = q.amount() == null ? null
                : new Money(q.amount(), blank(q.currency()) ? "EUR" : q.currency().trim().toUpperCase());
        LocalDate date = q.date() == null ? LocalDate.now() : q.date();
        RuleBasedAssignmentResolver resolver = new RuleBasedAssignmentResolver(new JdbcAssignmentRules(db), currency);
        List<Evaluation> ev = resolver.explain(new Query(upper(q.processType()), role, ctx, value, date));
        Map<Long, RuleRow> rows = new HashMap<>();
        for (RuleRow r : rules(adminId, role)) rows.put(r.id(), r);
        List<SimLine> lines = new ArrayList<>();
        boolean first = true;
        String outcome = null;
        for (Evaluation e : ev) {
            RuleRow r = rows.get(e.rule().id());
            if (r == null) continue;
            boolean win = first && e.compatible();
            if (win) {
                first = false;
                outcome = "Riceve: " + target(r) + " (regola " + r.id() + ")";
            }
            lines.add(new SimLine(r, win, e.excludedBecause()));
        }
        boolean assigned = outcome != null;
        if (!assigned)
            outcome = "Nessuna regola compatibile: il task andrebbe al team di riserva " + fallbackTeam;
        return new SimResult(outcome, assigned, lines);
    }

    static String target(RuleRow r) {
        if (r.userId() != null && r.teamCode() != null) return r.userName() + " con il team " + r.teamCode();
        if (r.userId() != null) return r.userName();
        return "team " + r.teamCode();
    }

    // ------------------------------------------------------------------ interni

    private List<RuleRow> rules(Connection c, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.id, a.role_code, a.process_type_code, a.purchasing_group, a.plant, a.storage_location,
                       a.amount_from, a.amount_to, a.currency, a.user_id, u.display_name, coalesce(u.active, true),
                       a.target_team_code, a.valid_from, a.valid_to
                  FROM ewf_org.assignment_rule a LEFT JOIN ewf_org.app_user u ON u.id = a.user_id
                 WHERE a.role_code = ? ORDER BY a.id""")) {
            ps.setString(1, role);
            List<RuleRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AssignmentRule ar = new AssignmentRule(rs.getLong(1), rs.getString(3), rs.getString(2), rs.getString(4),
                            rs.getString(5), rs.getString(6), null, null, null, null, null, null, null);
                    long uid = rs.getLong(10);
                    Long user = rs.wasNull() ? null : uid;
                    out.add(new RuleRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9), user,
                            rs.getString(11), rs.getBoolean(12), rs.getString(13), date(rs, 14), date(rs, 15),
                            ar.specificity()));
                }
            }
            out.sort((x, y) -> x.specificity() != y.specificity() ? Integer.compare(y.specificity(), x.specificity())
                    : Long.compare(x.id(), y.id()));
            return out;
        }
    }

    /** Due regole possono valere per lo stesso documento: criteri compatibili, fasce e validita' che si toccano. */
    private static boolean canOverlap(RuleRow a, RuleRow b) {
        if (!compatible(a.processType(), b.processType()) || !compatible(a.purchasingGroup(), b.purchasingGroup())
                || !compatible(a.plant(), b.plant()) || !compatible(a.storageLocation(), b.storageLocation()))
            return false;
        boolean amountsComparable = a.currency() == null || b.currency() == null || a.currency().equals(b.currency());
        if (amountsComparable && !rangesTouch(a.amountFrom(), a.amountTo(), b.amountFrom(), b.amountTo())) return false;
        return datesTouch(a.validFrom(), a.validTo(), b.validFrom(), b.validTo());
    }

    private static boolean compatible(String x, String y) {
        return x == null || y == null || x.equals(y);
    }

    private static boolean rangesTouch(BigDecimal f1, BigDecimal t1, BigDecimal f2, BigDecimal t2) {
        if (f1 != null && t2 != null && f1.compareTo(t2) > 0) return false;
        return f2 == null || t1 == null || f2.compareTo(t1) <= 0;
    }

    private static boolean datesTouch(LocalDate f1, LocalDate t1, LocalDate f2, LocalDate t2) {
        if (f1 != null && t2 != null && f1.isAfter(t2)) return false;
        return f2 == null || t1 == null || !f2.isAfter(t1);
    }

    private static long count(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        try {
            return db.inTransaction(work);
        } catch (JdbcException e) {
            SQLException s = e.getCause() instanceof SQLException x ? x : null;
            String state = s == null ? "" : String.valueOf(s.getSQLState());
            String msg = switch (state) {
                case "23505" -> "Esiste gia' un ruolo con questo codice";
                case "23503" -> "Riferimento inesistente (ruolo, utente o team)";
                default -> "Errore del database: " + e.getMessage();
            };
            throw new ServiceException(Code.INVALID, msg);
        }
    }

    private static LocalDate date(ResultSet rs, int i) throws SQLException {
        java.sql.Date d = rs.getDate(i);
        return d == null ? null : d.toLocalDate();
    }

    private static void setDate(PreparedStatement ps, int i, LocalDate d) throws SQLException {
        if (d == null) ps.setNull(i, Types.DATE); else ps.setDate(i, java.sql.Date.valueOf(d));
    }

    private static void setDecimal(PreparedStatement ps, int i, BigDecimal v) throws SQLException {
        if (v == null) ps.setNull(i, Types.NUMERIC); else ps.setBigDecimal(i, v);
    }

    private static String upper(String s) {
        return blank(s) ? null : s.trim().toUpperCase();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
