package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.model.User;
import it.eone.ewf.engine.spi.UserDirectory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Utenti, pesi, sostituti e team da ewf_org. */
public final class JdbcUserDirectory implements UserDirectory {

    private final JdbcTransactions db;

    public JdbcUserDirectory(JdbcTransactions db) {
        this.db = db;
    }

    @Override public Optional<User> byId(long id) { return user("id = ?", id); }
    @Override public Optional<User> byLogin(String login) { return user("login = ?", login); }
    @Override public Optional<User> bySapUser(String sapUser) { return user("upper(sap_user) = upper(?)", sapUser); }

    private Optional<User> user(String where, Object param) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, login, display_name, sap_user, active FROM ewf_org.app_user WHERE " + where)) {
                ps.setObject(1, param);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new User(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getBoolean(5)));
                }
            }
        });
    }

    @Override
    public int effectiveWeight(long userId, String processType) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ewf_org.effective_weight(?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, processType);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public boolean isSubstitute(long substituteId, long userId, LocalDate date) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT 1 FROM ewf_org.substitution
                     WHERE user_id = ? AND substitute_id = ? AND ? BETWEEN valid_from AND valid_to""")) {
                ps.setLong(1, userId);
                ps.setLong(2, substituteId);
                ps.setObject(3, date);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public Set<Long> teamMembers(String teamCode, LocalDate date) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT m.user_id FROM ewf_org.team_member m JOIN ewf_org.app_user u ON u.id = m.user_id
                     WHERE m.team_code = ? AND u.active
                       AND (m.valid_from IS NULL OR m.valid_from <= ?) AND (m.valid_to IS NULL OR m.valid_to >= ?)""")) {
                ps.setString(1, teamCode);
                ps.setObject(2, date);
                ps.setObject(3, date);
                Set<Long> out = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getLong(1));
                }
                return out;
            }
        });
    }

    @Override
    public Set<String> teamsOf(long userId, LocalDate date) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT team_code FROM ewf_org.team_member
                     WHERE user_id = ?
                       AND (valid_from IS NULL OR valid_from <= ?) AND (valid_to IS NULL OR valid_to >= ?)""")) {
                ps.setLong(1, userId);
                ps.setObject(2, date);
                ps.setObject(3, date);
                Set<String> out = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return out;
            }
        });
    }

    @Override
    public Set<Long> substitutedBy(long substituteId, LocalDate date) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT user_id FROM ewf_org.substitution
                     WHERE substitute_id = ? AND ? BETWEEN valid_from AND valid_to""")) {
                ps.setLong(1, substituteId);
                ps.setObject(2, date);
                Set<Long> out = new HashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getLong(1));
                }
                return out;
            }
        });
    }
}
