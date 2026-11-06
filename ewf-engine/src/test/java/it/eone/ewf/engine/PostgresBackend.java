package it.eone.ewf.engine;

import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.jdbc.*;
import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.spi.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Backend su un database di TEST (mai quello di lavoro: ad ogni test le tabelle ewf_* vengono svuotate).
 * Variabili d'ambiente: EWF_TEST_DB_URL, EWF_TEST_DB_USER, EWF_TEST_DB_PASSWORD.
 * L'utente deve poter fare TRUNCATE: usare il proprietario dello schema (ewf_owner).
 */
final class PostgresBackend implements Backend {

    static boolean configured() {
        return System.getenv("EWF_TEST_DB_URL") != null;
    }

    private final JdbcTransactions db;
    private final JdbcDefinitionStore defs;
    private final JdbcDefinitionWriter defWriter;
    private final JdbcRuntimeStore store;
    private final JdbcUserDirectory users;
    private final JdbcOrgWriter org;
    private final JdbcAssignmentRules rules;
    private final JdbcAuditSink audit;

    PostgresBackend() {
        SimpleDataSource ds = new SimpleDataSource(System.getenv("EWF_TEST_DB_URL"),
                System.getenv("EWF_TEST_DB_USER"), System.getenv("EWF_TEST_DB_PASSWORD"));
        db = new JdbcTransactions(ds);
        truncateAll();
        defs = new JdbcDefinitionStore(db);
        defWriter = new JdbcDefinitionWriter(db);
        store = new JdbcRuntimeStore(db);
        users = new JdbcUserDirectory(db);
        org = new JdbcOrgWriter(db);
        rules = new JdbcAssignmentRules(db);
        audit = new JdbcAuditSink(db);
    }

    private void truncateAll() {
        db.with(c -> {
            String tables;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
                      FROM pg_tables WHERE schemaname IN ('ewf_core', 'ewf_org')""")) {
                rs.next();
                tables = rs.getString(1);
            }
            try (Statement st = c.createStatement()) {
                st.execute("TRUNCATE " + tables + " RESTART IDENTITY CASCADE");
            }
            return null;
        });
    }

    @Override public DefinitionStore defs() { return defs; }
    @Override public RuntimeStore store() { return store; }
    @Override public UserDirectory users() { return users; }
    @Override public Supplier<List<AssignmentRule>> rules() { return rules; }
    @Override public AuditSink audit() { return audit; }
    @Override public Transactions tx() { return db; }

    @Override public void addUser(long id, String login, String name, String sap, int w) { org.addUser(id, login, name, sap, w); }
    @Override public void deactivate(long id) { org.setActive(id, false); }
    @Override public void processWeight(long u, String t, int w) { org.processWeight(u, t, w); }
    @Override public void substitution(long u, long s, LocalDate f, LocalDate t) { org.substitution(u, s, f, t); }
    @Override public void teamMember(String team, long u) { org.teamMember(team, u); }
    @Override public void addRule(AssignmentRule r) { org.addRule(r); }
    @Override public void removeRules(String role) { org.deleteRules(role); }
    @Override public void defineType(ProcessType t) { defWriter.saveType(t); }
    @Override public long activate(ProcessVersion v) { return defWriter.saveVersion(v, true); }

    int countAudit(String type) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM ewf_core.audit_event WHERE event_type = ?")) {
                ps.setString(1, type);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public List<String> auditTypes(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT event_type FROM ewf_core.audit_event WHERE process_id = ? ORDER BY id")) {
                ps.setLong(1, processId);
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return out;
            }
        });
    }

}
