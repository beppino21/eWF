package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.jdbc.JdbcDefinitionWriter;
import it.eone.ewf.engine.jdbc.JdbcOrgWriter;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * Scrive il piano nel DB in UNA transazione: o tutto o niente.
 * Prova generale (dryRun): scrive, valida, attiva... e poi annulla tutto con un rollback.
 * Cosi' anche la prova passa dalle stesse verifiche del DB (vincoli, validate_version).
 *
 * Richiede un DB senza processi avviati: prima di scrivere svuota definizioni e organizzazione.
 * Va eseguito con il proprietario dello schema (ewf_owner).
 */
public final class MigrationWriter {

    public record VersionOutcome(String processType, Long versionId, boolean active,
                                 List<JdbcDefinitionWriter.Problem> problems) {}

    public record Result(boolean dryRun, boolean committed, List<VersionOutcome> versions, Map<String, Integer> written) {}

    private static final class DryRunRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
        DryRunRollback() { super("prova generale: rollback"); }
    }

    private final JdbcTransactions db;

    public MigrationWriter(JdbcTransactions db) {
        this.db = db;
    }

    public Result write(MigrationPlan plan, boolean dryRun) {
        List<VersionOutcome> versions = new ArrayList<>();
        Map<String, Integer> written = new LinkedHashMap<>();
        try {
            db.inTransaction(() -> {
                requireNoProcesses();
                wipe();
                JdbcOrgWriter org = new JdbcOrgWriter(db);
                JdbcDefinitionWriter defs = new JdbcDefinitionWriter(db);

                // organizzazione
                plan.roles.forEach(org::ensureRole);
                plan.teams.forEach(org::ensureTeam);
                Map<String, Long> ids = new HashMap<>();
                for (MigrationPlan.User u : plan.users)
                    ids.put(u.login(), org.addUser(null, u.login(), u.displayName(), u.sapUser(), u.weight(), u.email(), u.active()));
                for (MigrationPlan.Member m : plan.members) org.teamMember(m.team(), ids.get(m.userLogin()));
                for (MigrationPlan.Rule r : plan.rules)
                    org.addRule(new AssignmentRule(0, r.processType(), r.role(), r.purchasingGroup(), r.plant(),
                            r.storageLocation(), r.amountFrom(), r.amountTo(), r.currency(), ids.get(r.userLogin()),
                            null, r.validFrom(), r.validTo()));
                for (MigrationPlan.Substitution s : plan.substitutions)
                    org.substitution(ids.get(s.userLogin()), ids.get(s.substituteLogin()), s.from(), s.to());
                for (MigrationPlan.Weight w : plan.weights)
                    org.processWeight(ids.get(w.userLogin()), w.processType(), w.weight());

                // catalogo handler: sono FM ABAP, eseguite tramite /EWF/ENGINE_DISPATCH
                plan.handlers.forEach((code, contract) ->
                        defs.ensureHandler(code, "RFC_FM", contract, "migrato da /EWF/"));

                // processi: salvati in bozza, validati, attivati solo se senza errori
                for (ProcessType t : plan.types) defs.saveType(t);
                for (ProcessVersion v : plan.versions) {
                    long id = defs.saveVersion(v, false);
                    List<JdbcDefinitionWriter.Problem> problems = defs.validate(id);
                    boolean ok = problems.stream().noneMatch(p -> p.severity().equals("ERROR"));
                    if (ok) defs.activate(id);
                    versions.add(new VersionOutcome(v.processTypeCode(), id, ok, problems));
                }

                written.put("Ruoli", plan.roles.size());
                written.put("Team", plan.teams.size());
                written.put("Utenti", plan.users.size());
                written.put("Membri di team", plan.members.size());
                written.put("Regole di assegnazione", plan.rules.size());
                written.put("Sostituzioni", plan.substitutions.size());
                written.put("Pesi per processo", plan.weights.size());
                written.put("Handler", plan.handlers.size());
                written.put("Tipi processo", plan.types.size());
                written.put("Versioni", versions.size());
                written.put("Versioni attivate", (int) versions.stream().filter(VersionOutcome::active).count());

                if (dryRun) throw new DryRunRollback();
                return null;
            });
        } catch (DryRunRollback expected) {
            return new Result(true, false, versions, written);
        }
        return new Result(false, true, versions, written);
    }

    private void requireNoProcesses() {
        long n = db.with(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM ewf_core.process_instance")) {
                rs.next();
                return rs.getLong(1);
            }
        });
        if (n > 0)
            throw new IllegalStateException("Il DB contiene " + n + " processi: la migrazione richiede un DB senza processi avviati");
    }

    /** Svuota definizioni e organizzazione (il runtime e' gia' vuoto). */
    private void wipe() {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("""
                        TRUNCATE ewf_core.task_hook, ewf_core.task_option, ewf_core.task_def, ewf_core.step_def,
                                 ewf_core.process_version, ewf_core.process_type, ewf_core.doc_type, ewf_core.handler,
                                 ewf_org.assignment_rule, ewf_org.assignment_exception, ewf_org.role_group_member,
                                 ewf_org.role_team_auth, ewf_org.user_process_auth, ewf_org.substitution,
                                 ewf_org.team_member, ewf_org.app_user, ewf_org.team, ewf_org.role,
                                 ewf_org.user_class, ewf_org.area
                        RESTART IDENTITY CASCADE""");
            }
            return null;
        });
    }

    /** Conteggio righe per il riepilogo dopo l'applicazione. */
    public Map<String, Long> countRows() {
        return db.with(c -> {
            Map<String, Long> out = new LinkedHashMap<>();
            for (String t : List.of("ewf_core.process_type", "ewf_core.process_version", "ewf_core.step_def",
                    "ewf_core.task_def", "ewf_core.task_hook", "ewf_core.handler", "ewf_org.app_user",
                    "ewf_org.assignment_rule", "ewf_org.substitution", "ewf_org.team_member")) {
                try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM " + t);
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    out.put(t, rs.getLong(1));
                }
            }
            return out;
        });
    }
}
