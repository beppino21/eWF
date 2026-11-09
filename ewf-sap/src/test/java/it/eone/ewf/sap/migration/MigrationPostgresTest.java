package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcEngineFactory;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.JdbcUserDirectory;
import it.eone.ewf.engine.jdbc.SimpleDataSource;
import it.eone.ewf.engine.memory.MapDocumentAdapters;
import it.eone.ewf.engine.memory.MapHandlerRegistry;
import it.eone.ewf.engine.memory.RateCurrencyConverter;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.ProcessInstance;
import it.eone.ewf.engine.model.ProcessStatus;
import it.eone.ewf.engine.model.TaskInstance;
import it.eone.ewf.engine.spi.HandlerResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Migrazione su PostgreSQL e poi un OdA vero attraverso il motore, sulla definizione migrata.
 * Gira solo con EWF_TEST_DB_URL (database di TEST: viene svuotato).
 */
class MigrationPostgresTest {

    private JdbcTransactions db;

    @BeforeEach
    void setUp() {
        assumeTrue(System.getenv("EWF_TEST_DB_URL") != null, "EWF_TEST_DB_URL non impostata");
        db = new JdbcTransactions(new SimpleDataSource(System.getenv("EWF_TEST_DB_URL"),
                System.getenv("EWF_TEST_DB_USER"), System.getenv("EWF_TEST_DB_PASSWORD")));
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("TRUNCATE ewf_core.process_instance, ewf_core.audit_event RESTART IDENTITY CASCADE");
            }
            return null;
        });
    }

    private long count(String table) {
        return db.with(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void provaGeneraleLasciaIlDbComeEra() throws Exception {
        long users = count("ewf_org.app_user"), versions = count("ewf_core.process_version"),
             rules = count("ewf_org.assignment_rule");
        MigrationPlan plan = new LegacyMapper(LegacyMapperTest.ALL_BUT_TRAVEL).map(LegacyMapperTest.realConfig());
        MigrationWriter.Result r = new MigrationWriter(db).write(plan, true);
        assertTrue(r.dryRun());
        assertEquals(13, r.versions().size(), "la prova arriva fino alla validazione");
        assertEquals(users, count("ewf_org.app_user"), "nemmeno la pulizia iniziale resta");
        assertEquals(versions, count("ewf_core.process_version"));
        assertEquals(rules, count("ewf_org.assignment_rule"));
    }

    @Test
    void conIDefaultTuttiIProcessiSonoAttivabili() throws Exception {
        MigrationPlan plan = new LegacyMapper(LegacyMapper.Options.defaults()).map(LegacyMapperTest.realConfig());
        MigrationWriter.Result r = new MigrationWriter(db).write(plan, true);
        assertEquals(5, r.versions().size());
        assertTrue(r.versions().stream().allMatch(MigrationWriter.VersionOutcome::active), r.versions().toString());
    }

    @Test
    void migrazioneEPoiUnOdaVero() throws Exception {
        MigrationPlan plan = new LegacyMapper(LegacyMapperTest.ALL_BUT_TRAVEL).map(LegacyMapperTest.realConfig());
        MigrationWriter.Result r = new MigrationWriter(db).write(plan, false);

        Map<String, Boolean> active = new TreeMap<>();
        r.versions().forEach(v -> active.put(v.processType(), v.active()));
        assertTrue(active.get("EONE_ODA"));
        assertTrue(active.get("SBLOCCO_PAGAMENTO"));
        assertFalse(active.get("UC_MULTE"), "STEP_KO = 00000: resta in bozza");
        assertFalse(active.get("UC_EM_APPROVE_ODA"));
        assertEquals(5, count("ewf_org.app_user"));

        // un OdA attraverso il motore, sulla definizione appena migrata
        List<Map<String, String>> releaseParams = new ArrayList<>();
        MapHandlerRegistry handlers = new MapHandlerRegistry()
                .register("/EWF/WF_CHECK_LOCK_EKKO", ctx -> HandlerResult.ok())
                .register("/EWF/SEND_MAIL_NEW_TASK", ctx -> HandlerResult.ok())
                .register("/EWF/WF_RIL_ODA", ctx -> { releaseParams.add(ctx.params()); return HandlerResult.ok(); });
        MapDocumentAdapters.Fixed po = new MapDocumentAdapters.Fixed().value("4500001053", Money.of("5000", "EUR"));
        WorkflowEngine engine = JdbcEngineFactory.create(
                new SimpleDataSource(System.getenv("EWF_TEST_DB_URL"), System.getenv("EWF_TEST_DB_USER"),
                        System.getenv("EWF_TEST_DB_PASSWORD")),
                handlers, new MapDocumentAdapters().register("F", po), new RateCurrencyConverter(),
                Clock.systemUTC(), EngineConfig.defaults());
        JdbcUserDirectory users = new JdbcUserDirectory(db);
        long rossi = users.byLogin("ROSSI").orElseThrow().id();
        long bianchi = users.byLogin("BIANCHI").orElseThrow().id();
        long verdi = users.byLogin("VERDI").orElseThrow().id();
        long neri = users.byLogin("NERI").orElseThrow().id();

        ProcessInstance p = engine.start(new Requests.Start("EONE_ODA", "4500001053", null, rossi, null));
        it.eone.ewf.engine.jdbc.JdbcRuntimeStore store = new it.eone.ewf.engine.jdbc.JdbcRuntimeStore(db);
        for (long[] step : new long[][]{{10, rossi}, {20, bianchi}, {30, verdi}, {40, neri}}) {
            TaskInstance t = store.tasksOf(p.id).stream().filter(TaskInstance::isOpen).findFirst().orElseThrow();
            assertEquals(step[0], t.stepNo);
            assertEquals(Long.valueOf(step[1]), t.assigneeId, "assegnatario dello step " + step[0]);
            engine.act(new Requests.Act(t.id, step[1], Requests.Action.OK, null, null));
        }
        assertEquals(ProcessStatus.COMPLETED, store.process(p.id).orElseThrow().status);
        assertEquals("01", releaseParams.get(0).get("Z_WF_SET_RIL"), "codice di rilascio migrato");
    }
}
