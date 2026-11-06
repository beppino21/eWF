package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.assign.AssignmentRule;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Regole di assegnazione da ewf_org.assignment_rule, per RuleBasedAssignmentResolver. */
public final class JdbcAssignmentRules implements Supplier<List<AssignmentRule>> {

    private final JdbcTransactions db;

    public JdbcAssignmentRules(JdbcTransactions db) {
        this.db = db;
    }

    @Override
    public List<AssignmentRule> get() {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, process_type_code, role_code, purchasing_group, plant, storage_location,
                           amount_from, amount_to, currency, user_id, target_team_code, valid_from, valid_to
                      FROM ewf_org.assignment_rule ORDER BY id""");
                 ResultSet rs = ps.executeQuery()) {
                List<AssignmentRule> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new AssignmentRule(rs.getLong("id"), rs.getString("process_type_code"),
                            rs.getString("role_code"), rs.getString("purchasing_group"), rs.getString("plant"),
                            rs.getString("storage_location"), rs.getBigDecimal("amount_from"),
                            rs.getBigDecimal("amount_to"), rs.getString("currency"), Sql.getLong(rs, "user_id"),
                            rs.getString("target_team_code"), Sql.getDate(rs, "valid_from"),
                            Sql.getDate(rs, "valid_to")));
                }
                return out;
            }
        });
    }
}
