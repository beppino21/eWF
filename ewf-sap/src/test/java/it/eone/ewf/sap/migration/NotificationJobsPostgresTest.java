package it.eone.ewf.sap.migration;

import it.eone.connect.db.ConnectSchema;
import it.eone.connect.db.Db;
import it.eone.connect.scheduler.JobContext;
import it.eone.connect.scheduler.JobResult;
import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcEngineFactory;
import it.eone.ewf.engine.jdbc.JdbcRuntimeStore;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.JdbcUserDirectory;
import it.eone.ewf.engine.jdbc.SimpleDataSource;
import it.eone.ewf.engine.memory.MapDocumentAdapters;
import it.eone.ewf.engine.memory.MapHandlerRegistry;
import it.eone.ewf.engine.memory.RateCurrencyConverter;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.ProcessInstance;
import it.eone.ewf.engine.model.TaskInstance;
import it.eone.ewf.engine.spi.HandlerResult;
import it.eone.ewf.notify.DigestJob;
import it.eone.ewf.notify.MailTemplates;
import it.eone.ewf.notify.NotifyJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * (Nel pacchetto della migrazione per riusarne i dati di prova.)
 * Avvisi immediati e riepilogo su PostgreSQL, sul processo EONE_ODA migrato dal legacy.
 * Step 20 (Rilascio Responsabile Acquisti) con avviso immediato. Solo con EWF_TEST_DB_URL (DB di TEST).
 */
class NotificationJobsPostgresTest {

    private SimpleDataSource ds;
    private JdbcTransactions db;
    private WorkflowEngine engine;
    private JdbcRuntimeStore store;
    private long rossi, bianchi, verdi;

    record Mail(String to, String subject, String body, String html) {}

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(System.getenv("EWF_TEST_DB_URL") != null, "EWF_TEST_DB_URL non impostata");
        ds = new SimpleDataSource(System.getenv("EWF_TEST_DB_URL"), System.getenv("EWF_TEST_DB_USER"),
                System.getenv("EWF_TEST_DB_PASSWORD"));
        db = new JdbcTransactions(ds);
        ConnectSchema.migrate(new Db(ds), null);
        sql("TRUNCATE ewf_core.process_instance, ewf_core.audit_event RESTART IDENTITY CASCADE",
            "TRUNCATE eone_connect.mail_outbox RESTART IDENTITY");
        new MigrationWriter(db).write(new LegacyMapper(LegacyMapperTest.ALL_BUT_TRAVEL).map(LegacyMapperTest.realConfig()), false);
        // i dati reali contengono sostituzioni (es. BIANCHI -> NERI): ogni test parte senza, e mette le sue
        sql("DELETE FROM ewf_org.substitution");
        sql("UPDATE ewf_org.app_user SET email = lower(login) || '@cliente.it', mail_every_days = 1, last_mail_on = NULL",
            """
            UPDATE ewf_core.task_def SET notify = 'IMMEDIATE' WHERE id IN (
              SELECT d.id FROM ewf_core.task_def d JOIN ewf_core.step_def s ON s.id = d.step_id
                JOIN ewf_core.process_version v ON v.id = s.version_id
               WHERE v.process_type_code = 'EONE_ODA' AND s.step_no = 20)""");
        engine = JdbcEngineFactory.create(ds,
                new MapHandlerRegistry().register("/EWF/WF_CHECK_LOCK_EKKO", ctx -> HandlerResult.ok())
                        .register("/EWF/WF_RIL_ODA", ctx -> HandlerResult.ok()),
                new MapDocumentAdapters().register("F", new MapDocumentAdapters.Fixed()
                        .value("4500001053", Money.of("5000", "EUR")).context("4500001053", Map.of("sap.LIFNR", "0011000141"))),
                new RateCurrencyConverter(), Clock.systemUTC(), EngineConfig.defaults());
        store = new JdbcRuntimeStore(db);
        JdbcUserDirectory users = new JdbcUserDirectory(db);
        rossi = users.byLogin("ROSSI").orElseThrow().id();
        bianchi = users.byLogin("BIANCHI").orElseThrow().id();
        verdi = users.byLogin("VERDI").orElseThrow().id();
    }

    private void sql(String... statements) {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                for (String s : statements) st.execute(s);
            }
            return null;
        });
    }

    private List<Mail> mails() {
        return db.with(c -> {
            List<Mail> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT to_addr, subject, body_text, body_html FROM eone_connect.mail_outbox ORDER BY id")) {
                while (rs.next()) out.add(new Mail(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
            return out;
        });
    }

    private String displayName(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT display_name FROM ewf_org.app_user WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private static JobContext ctx(Map<String, String> params) {
        return new JobContext("T", 1L, "TEST", params, () -> false);
    }

    /** OdA avviato e approvato da ROSSI allo step 10: il task dello step 20 va a BIANCHI. */
    private TaskInstance odaAtStep20() {
        ProcessInstance p = engine.start(new Requests.Start("EONE_ODA", "4500001053", null, rossi, null));
        TaskInstance t10 = store.tasksOf(p.id).stream().filter(TaskInstance::isOpen).findFirst().orElseThrow();
        engine.act(new Requests.Act(t10.id, rossi, Requests.Action.OK, null, null));
        TaskInstance t20 = store.tasksOf(p.id).stream().filter(TaskInstance::isOpen).findFirst().orElseThrow();
        assertEquals(20, t20.stepNo);
        assertEquals(Long.valueOf(bianchi), t20.assigneeId);
        return t20;
    }

    @Test
    void senzaParametroIlLinkUsaLIndirizzoDellApplicazione() throws Exception {
        TaskInstance t = odaAtStep20();
        NotifyJob job = new NotifyJob(ds, Clock.systemDefaultZone(), "https://ewf.cliente.it/ewfserver");
        JobResult r = job.run(ctx(Map.of()));
        assertEquals(JobResult.Status.OK, r.status(), r.message());
        List<Mail> m = mails();
        assertEquals(1, m.size());
        assertTrue(m.get(0).body().contains("https://ewf.cliente.it/ewfserver/Outest.risc?task=" + t.id), m.get(0).body());
    }

    @Test
    void avvisoImmediatoAlDestinatarioUnaVoltaSola() throws Exception {
        TaskInstance t = odaAtStep20();
        NotifyJob job = new NotifyJob(ds, Clock.systemDefaultZone());
        JobResult r = job.run(ctx(Map.of("baseUrl", "https://ewf.cliente.it/")));
        assertEquals(JobResult.Status.OK, r.status(), r.message());
        List<Mail> m = mails();
        assertEquals(1, m.size(), "solo lo step 20 prevede l'avviso (lo step 10 no)");
        assertEquals("bianchi@cliente.it", m.get(0).to());
        assertEquals("eWF: Rilascio Responsabile Acquisti - OdA 4500001053", m.get(0).subject());
        assertTrue(m.get(0).body().contains("5.000,00 EUR"), m.get(0).body());
        assertTrue(m.get(0).body().contains("Fornitore:  11000141"), m.get(0).body());
        assertTrue(m.get(0).body().contains("https://ewf.cliente.it/Outest.risc?task=" + t.id), m.get(0).body());
        assertTrue(m.get(0).html().contains("Rilascio Responsabile Acquisti"));
        assertEquals(JobResult.Status.IDLE, job.run(ctx(Map.of())).status(), "notifica gia' elaborata");
        assertEquals(1, mails().size());
    }

    @Test
    void conUnSostitutoAttivoLAvvisoVaAlSostituto() throws Exception {
        sql("INSERT INTO ewf_org.substitution(user_id, substitute_id, valid_from, valid_to) VALUES ("
                + bianchi + ", " + verdi + ", current_date - 1, current_date + 7)");
        odaAtStep20();
        new NotifyJob(ds, Clock.systemDefaultZone()).run(ctx(Map.of()));
        List<Mail> m = mails();
        assertEquals(1, m.size());
        assertEquals("verdi@cliente.it", m.get(0).to());
        assertTrue(m.get(0).subject().contains("in sostituzione di " + displayName(bianchi)), m.get(0).subject());
    }

    @Test
    void taskGiaChiusoNessunaMail() throws Exception {
        TaskInstance t = odaAtStep20();
        engine.act(new Requests.Act(t.id, bianchi, Requests.Action.OK, null, null));   // approvato prima del giro
        new NotifyJob(ds, Clock.systemDefaultZone()).run(ctx(Map.of()));
        assertTrue(mails().isEmpty());
        String result = db.with(c -> {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT result FROM ewf_core.notification")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertTrue(result.startsWith("task non piu' aperto"), result);
    }

    @Test
    void riepilogoSoloAChiHaAttivitaEUnaVoltaPerPeriodo() throws Exception {
        odaAtStep20();
        DigestJob job = new DigestJob(ds, Clock.systemDefaultZone());
        JobResult r = job.run(ctx(Map.of()));
        assertEquals(JobResult.Status.OK, r.status(), r.message());
        List<Mail> m = mails();
        assertEquals(1, m.size(), "ROSSI ha gia' agito, VERDI e NERI non hanno attivita': nessuna mail per loro");
        assertEquals("bianchi@cliente.it", m.get(0).to());
        assertEquals("eWF: 1 attivita' in attesa", m.get(0).subject());
        assertTrue(m.get(0).body().contains("- OdA 4500001053 | Rilascio Responsabile Acquisti | 5.000,00 EUR"),
                m.get(0).body());
        assertTrue(m.get(0).body().contains("in attesa da oggi"));
        assertEquals(JobResult.Status.IDLE, job.run(ctx(Map.of())).status(), "stesso giorno: niente secondo riepilogo");

        DigestJob tomorrow = new DigestJob(ds, Clock.offset(Clock.systemDefaultZone(), java.time.Duration.ofDays(1)));
        tomorrow.run(ctx(Map.of()));
        assertEquals(2, mails().size(), "passato un giorno (mail_every_days = 1): nuovo riepilogo");
    }

    @Test
    void riepilogoDelSostitutoContieneIlLavoroDelTitolare() throws Exception {
        odaAtStep20();
        sql("INSERT INTO ewf_org.substitution(user_id, substitute_id, valid_from, valid_to) VALUES ("
                + bianchi + ", " + verdi + ", current_date, current_date)");
        new DigestJob(ds, Clock.systemDefaultZone()).run(ctx(Map.of()));
        List<Mail> m = mails();
        assertEquals(1, m.size(), "BIANCHI e' sostituito: il suo lavoro lo vede VERDI");
        assertEquals("verdi@cliente.it", m.get(0).to());
        assertTrue(m.get(0).body().contains("(per " + displayName(bianchi) + ")"), m.get(0).body());
    }

    @Test
    void segnapostoEValoriSicuriNellHtml() {
        MailTemplates.Rendered r = MailTemplates.render(
                new MailTemplates.Template("Ciao {{user}}", "{{user}} {{x}}", "<p>{{user}}</p>{{rowsHtml}}"),
                Map.of("user", "<script>", "rowsHtml", "<b>ok</b>"));
        assertEquals("<script> ", r.bodyText(), "segnaposto mancante = vuoto");
        assertEquals("<p>&lt;script&gt;</p><b>ok</b>", r.bodyHtml(), "valori resi sicuri, blocchi *Html no");
    }
}
