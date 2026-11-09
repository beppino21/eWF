package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.assign.AssignmentRule;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Scrittura dell'organizzazione (utenti, pesi, sostituti, team, regole).
 * Base per la migrazione da /EWF/USER0, USER_TASK, SOSTITUTI e per l'amministrazione.
 */
public final class JdbcOrgWriter {

    private final JdbcTransactions db;

    public JdbcOrgWriter(JdbcTransactions db) {
        this.db = db;
    }

    /**
     * @param id se valorizzato forza l'id (utile in migrazione e nei test); se null lo genera il DB
     * @return id dell'utente
     */
    public long addUser(Long id, String login, String displayName, String sapUser, int weight) {
        return addUser(id, login, displayName, sapUser, weight, null, true);
    }

    public long addUser(Long id, String login, String displayName, String sapUser, int weight,
                        String email, boolean active) {
        return db.with(c -> {
            String sql = id == null
                    ? "INSERT INTO ewf_org.app_user(login, display_name, sap_user, weight, email, active) "
                      + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id"
                    : "INSERT INTO ewf_org.app_user(id, login, display_name, sap_user, weight, email, active) "
                      + "OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                if (id != null) ps.setLong(i++, id);
                ps.setString(i++, login);
                ps.setString(i++, displayName);
                ps.setString(i++, sapUser);
                ps.setInt(i++, weight);
                ps.setString(i++, email);
                ps.setBoolean(i, active);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    public void setActive(long userId, boolean active) {
        update("UPDATE ewf_org.app_user SET active = ?, updated_at = now() WHERE id = ?", active, userId);
    }

    public void processWeight(long userId, String processType, int weight) {
        update("""
                INSERT INTO ewf_org.user_process_auth(user_id, process_type_code, auth_level) VALUES (?, ?, ?)
                ON CONFLICT (user_id, process_type_code) DO UPDATE SET auth_level = EXCLUDED.auth_level,
                       active = true, changed_at = now()""", userId, processType, weight);
    }

    public void substitution(long userId, long substituteId, LocalDate from, LocalDate to) {
        update("INSERT INTO ewf_org.substitution(user_id, substitute_id, valid_from, valid_to) VALUES (?, ?, ?, ?)",
                userId, substituteId, from, to);
    }

    public void teamMember(String teamCode, long userId) {
        db.with(c -> {
            ensureTeam(c, teamCode);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ewf_org.team_member(team_code, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, teamCode);
                ps.setLong(2, userId);
                return ps.executeUpdate();
            }
        });
    }

    /** @return id generato della regola (l'id dell'oggetto passato e' ignorato) */
    public long addRule(AssignmentRule r) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ewf_org.role(code, description) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, r.roleCode());
                ps.setString(2, r.roleCode());
                ps.executeUpdate();
            }
            if (r.targetTeam() != null) ensureTeam(c, r.targetTeam());
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_org.assignment_rule(process_type_code, role_code, purchasing_group, plant,
                           storage_location, amount_from, amount_to, currency, user_id, target_team_code,
                           valid_from, valid_to)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setString(1, r.processType());
                ps.setString(2, r.roleCode());
                ps.setString(3, r.purchasingGroup());
                ps.setString(4, r.plant());
                ps.setString(5, r.storageLocation());
                Sql.setBigDecimal(ps, 6, r.amountFrom());
                Sql.setBigDecimal(ps, 7, r.amountTo());
                ps.setString(8, r.currency());
                Sql.setLong(ps, 9, r.userId());
                ps.setString(10, r.targetTeam());
                Sql.setDate(ps, 11, r.validFrom());
                Sql.setDate(ps, 12, r.validTo());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    public void ensureRole(String code, String description) {
        update("INSERT INTO ewf_org.role(code, description) VALUES (?, ?) ON CONFLICT (code) DO NOTHING",
                code, description == null || description.isBlank() ? code : description);
    }

    public void ensureTeam(String code, String description) {
        update("INSERT INTO ewf_org.team(code, description) VALUES (?, ?) ON CONFLICT (code) DO NOTHING",
                code, description == null || description.isBlank() ? code : description);
    }

    public void deleteRules(String roleCode) {
        update("DELETE FROM ewf_org.assignment_rule WHERE role_code = ?", roleCode);
    }

    private static void ensureTeam(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ewf_org.team(code, description) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, code);
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    private void update(String sql, Object... params) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
                return ps.executeUpdate();
            }
        });
    }
}
