package it.eone.ewf.service;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.jdbc.*;
import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.DocumentAdapter;
import it.eone.ewf.engine.spi.Handler;
import it.eone.ewf.engine.spi.HandlerContext;
import it.eone.ewf.engine.spi.HandlerResult;
import it.eone.ewf.service.ServiceException.Code;
import it.eone.ewf.service.Views.*;
import it.eone.ewf.service.auth.AuthService;
import it.eone.ewf.service.auth.PasswordHasher;
import it.eone.ewf.service.spi.DocRef;
import it.eone.ewf.service.spi.DocumentAttachments;
import it.eone.ewf.service.spi.DocumentSheets;
import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.spi.SapGuiLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Livello di servizio su un database di TEST (le tabelle ewf_core/ewf_org vengono svuotate a ogni test).
 * Variabili: EWF_TEST_DB_URL, EWF_TEST_DB_USER, EWF_TEST_DB_PASSWORD (proprietario dello schema, schema 0.14).
 */
public class EwfServicePostgresTest {

    static final long ROSSI = 1, BIANCHI = 2, VERDI = 3, NERI = 4;
    static final String ODA = "SVC_ODA";
    static final ZoneId ROME = ZoneId.of("Europe/Rome");

    /** Orologio spostabile: blocco del login, giorni di attesa. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");
        @Override public ZoneId getZone() { return ROME; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    MutableClock clock;
    JdbcTransactions db;
    WorkflowEngine engine;
    EwfService svc;
    AuthService auth;
    final Map<String, Handler> handlers = new HashMap<>();
    final FakeDocAttachments docAttachments = new FakeDocAttachments();
    final FakeSheets sheets = new FakeSheets();

    @BeforeEach
    void setUp() {
        assumeTrue(System.getenv("EWF_TEST_DB_URL") != null, "EWF_TEST_DB_URL non impostata");
        SimpleDataSource ds = new SimpleDataSource(System.getenv("EWF_TEST_DB_URL"),
                System.getenv("EWF_TEST_DB_USER"), System.getenv("EWF_TEST_DB_PASSWORD"));
        db = new JdbcTransactions(ds);
        truncate();
        clock = new MutableClock();

        JdbcOrgWriter org = new JdbcOrgWriter(db);
        org.addUser(ROSSI, "ROSSI", "Mario Rossi", "MROSSI", 0);
        org.addUser(BIANCHI, "BIANCHI", "Anna Bianchi", null, 100);
        org.addUser(VERDI, "VERDI", "Luca Verdi", "LVERDI", 100);
        org.addUser(NERI, "NERI", "Sara Neri", null, 100);
        org.teamMember("WFADM", VERDI);
        org.addRule(new AssignmentRule(1, null, "APPROVATORE", null, null, null, null, null, null, BIANCHI, null, null, null));

        JdbcDefinitionWriter defs = new JdbcDefinitionWriter(db);
        defs.saveType(new ProcessType(ODA, "Approvazione OdA", "F", false, false, false));
        defs.saveVersion(new ProcessVersion(1, ODA, 1, null, List.of(
                new StepDef(10, true, JoinPolicy.ALL, 20, null, null,
                        List.of(task(101, "Richiesta", null, AssigneeMode.STARTER, Interaction.WEB))),
                new StepDef(20, false, JoinPolicy.ALL, 30, 10, null,
                        List.of(task(102, "Approvazione responsabile", "APPROVATORE", AssigneeMode.RULES, Interaction.WEB))),
                new StepDef(30, false, JoinPolicy.ALL, null, 10, null,
                        List.of(task(103, "Registrazione in SAP", null, AssigneeMode.STARTER, Interaction.SAPGUI_ONLY))))), true);
        sql("UPDATE ewf_core.process_type SET list_summary = '{sap.LIFNR} {supplier_name} - {plant} {plant_name}',"
                + " list_columns = '[{\"label\":\"Fornitore\",\"key\":\"supplier_name\"},{\"label\":\"Divisione\",\"key\":\"plant\"}]'"
                + " WHERE code = '" + ODA + "'");
        sql("INSERT INTO ewf_core.handler (code, kind, contract, target) VALUES ('/T/FN_OK','JAVA','TASK_ACTION','x'),"
                + " ('/T/FN_KO','JAVA','TASK_ACTION','x')");
        sql("INSERT INTO ewf_core.task_function (task_def_id, code, label, handler_code, confirm_text, position)"
                + " SELECT id, 'SOLLECITO', 'Sollecita fornitore', '/T/FN_OK', 'Inviare il sollecito?', 1"
                + "   FROM ewf_core.task_def WHERE text = 'Approvazione responsabile'"
                + " UNION ALL SELECT id, 'ERRORE', 'Funzione che fallisce', '/T/FN_KO', null, 2"
                + "   FROM ewf_core.task_def WHERE text = 'Approvazione responsabile'");

        handlers.put("/T/FN_OK", ctx -> new HandlerResult(HandlerResult.Status.OK,
                "Sollecito inviato per " + ctx.process().documentKey() + " da " + ctx.actor().login(), null, null, null, null, false));
        handlers.put("/T/FN_KO", ctx -> HandlerResult.ko("Fornitore senza indirizzo mail"));

        DocumentAdapter po = key -> new DocumentAdapter.DocumentInfo(true, Money.of("1500.50", "EUR"),
                Map.of("sap.LIFNR", "100123", "supplier_name", "Acme Spa", "plant", "1000"), null);
        engine = JdbcEngineFactory.create(ds, code -> Optional.ofNullable(handlers.get(code)),
                docType -> "F".equals(docType) ? Optional.of(po) : Optional.empty(),
                (m, cur, d) -> m, clock, new EngineConfig("WFADM", ROME));
        svc = service(EwfService.Config.defaults(), new EwfService.Backends(docAttachments, sheets, new FakeSapLink()));
        auth = new AuthService(db, new PasswordHasher(1000), clock);
    }

    EwfService service(EwfService.Config cfg, EwfService.Backends b) {
        return new EwfService(engine, db, new JdbcUserDirectory(db), code -> Optional.ofNullable(handlers.get(code)),
                b, clock, ROME, cfg);
    }

    static TaskDef task(long id, String text, String role, AssigneeMode mode, Interaction interaction) {
        return new TaskDef(id, 0, text, role, 0, mode, null, false, null, PreviewMode.NAME, interaction, null, List.of());
    }

    long start(String ebeln) {
        return engine.start(new Requests.Start(ODA, ebeln, null, ROSSI, null)).id;
    }

    /** Processo arrivato all'approvazione di BIANCHI. */
    long atApproval(String ebeln) {
        long p = start(ebeln);
        svc.approve(ROSSI, onlyTask(ROSSI).taskId(), null, null);
        return p;
    }

    WorkItem onlyTask(long user) {
        List<WorkItem> items = svc.worklist(user, WorklistFilter.all()).items();
        assertEquals(1, items.size(), "un solo task in lista");
        return items.get(0);
    }

    // ------------------------------------------------------------------ gantt

    @Test
    void lineaDelTempoConAvvioTaskChiusiEAperti() {
        long p = atApproval("4500009003");                 // step 10 approvato, step 20 aperto per BIANCHI
        List<TimelineBar> bars = svc.timeline(BIANCHI, p);
        assertEquals("START", bars.get(0).status());
        assertTrue(bars.size() >= 3, "avvio + almeno un task chiuso + quello aperto");
        TimelineBar open = bars.get(bars.size() - 1);
        assertEquals("OPEN", open.status());
        assertNull(open.to(), "il task aperto non ha fine");
        assertTrue(bars.stream().anyMatch(b -> "OK".equals(b.status()) && b.to() != null));
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.timeline(NERI, p)).code(),
                "stesse regole di visibilita' del dettaglio");
    }

    // ------------------------------------------------------------------ sostituzioni

    SubstitutionService subst() { return new SubstitutionService(db, clock, ROME); }

    @Test
    void sostituzioneIndicataDallUtenteEUsataDalMotore() {
        sql("UPDATE ewf_org.app_user SET can_substitute = true WHERE id = " + ROSSI);
        LocalDate today = LocalDate.of(2026, 11, 6);
        SubstitutionService.Substitution s = subst().add(ROSSI, BIANCHI, today, today.plusDays(5));
        assertTrue(s.activeToday());
        assertEquals("Anna Bianchi", s.substituteName());
        assertEquals(1, subst().asSubstitute(BIANCHI).size(), "BIANCHI vede chi sta sostituendo");

        start("4500009001");   // lo step 10 va a ROSSI: BIANCHI lo vede come sostituto
        WorkItem w = svc.worklist(BIANCHI, WorklistFilter.all()).items().stream()
                .filter(i -> "4500009001".equals(i.documentKey())).findFirst().orElseThrow();
        assertEquals(Reason.SUBSTITUTE, w.reason());

        ServiceException overlap = assertThrows(ServiceException.class,
                () -> subst().add(ROSSI, NERI, today.plusDays(3), today.plusDays(9)));
        assertEquals(Code.INVALID, overlap.code(), "periodi sovrapposti");
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> subst().add(ROSSI, NERI, today.minusDays(1), today)).code(), "niente periodi nel passato");
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> subst().add(ROSSI, ROSSI, today.plusDays(10), today.plusDays(11))).code());
    }

    @Test
    void togliereUnaSostituzioneInCorsoLaChiudeAIeriUnaFuturaLaCancella() {
        sql("UPDATE ewf_org.app_user SET can_substitute = true WHERE id = " + ROSSI);
        LocalDate today = LocalDate.of(2026, 11, 6);
        sql("INSERT INTO ewf_org.substitution (user_id, substitute_id, valid_from, valid_to) VALUES ("
                + ROSSI + ", " + BIANCHI + ", DATE '2026-11-01', DATE '2026-11-10')");
        SubstitutionService.Substitution future = subst().add(ROSSI, NERI, today.plusDays(20), today.plusDays(25));
        long running = subst().mine(ROSSI).stream().filter(SubstitutionService.Substitution::activeToday).findFirst().orElseThrow().id();

        subst().remove(ROSSI, running);
        subst().remove(ROSSI, future.id());
        assertTrue(subst().mine(ROSSI).isEmpty());
        assertEquals("2026-11-05", value("SELECT valid_to::text FROM ewf_org.substitution WHERE id = " + running),
                "la sostituzione in corso resta nello storico, chiusa a ieri");
        assertEquals("0", value("SELECT count(*)::text FROM ewf_org.substitution WHERE id = " + future.id()));
    }

    @Test
    void senzaPermessoNonSiGestisconoLeSostituzioni() {
        assertFalse(subst().canManage(NERI));
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class,
                () -> subst().add(NERI, BIANCHI, LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8))).code());
        sql("UPDATE ewf_org.user_class SET can_substitute = true WHERE code = (SELECT user_class_code FROM ewf_org.app_user WHERE id = "
                + NERI + ")");
        sql("UPDATE ewf_org.app_user SET can_substitute = NULL WHERE id = " + NERI);
        assertEquals(value("SELECT (user_class_code IS NOT NULL)::text FROM ewf_org.app_user WHERE id = " + NERI).equals("true"),
                subst().canManage(NERI), "senza indicazione sull'utente vale la sua classe");
    }

    // ------------------------------------------------------------------ amministrazione

    AdminService admin() { return new AdminService(engine, db, "WFADM", clock, ROME); }

    @Test
    void amministratoreEChiStaNelTeamDiRiserva() {
        assertTrue(admin().isAdmin(VERDI));
        assertFalse(admin().isAdmin(BIANCHI));
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> admin().processesInError(BIANCHI)).code());
    }

    @Test
    void esitoIncertoInSapLAmministratoreConfermaEIlProcessoAvanza() {
        sql("INSERT INTO ewf_core.handler (code, kind, contract, target) VALUES ('/T/INCERTO','JAVA','TASK_ACTION','x')");
        sql("INSERT INTO ewf_core.task_hook (task_def_id, event, seq, handler_id)"
                + " SELECT d.id, 'ON_OK', 1, h.id FROM ewf_core.task_def d, ewf_core.handler h"
                + "  WHERE d.text = 'Approvazione responsabile' AND h.code = '/T/INCERTO'");
        handlers.put("/T/INCERTO", ctx -> HandlerResult.unknown("timeout RFC"));
        long p = atApproval("4500009002");

        Requests.Outcome o = svc.approve(BIANCHI, onlyTask(BIANCHI).taskId(), null, null);
        assertEquals(Requests.Outcome.Result.UNKNOWN, o.result());

        List<AdminService.ProcessError> errors = admin().processesInError(VERDI);
        assertEquals(1, errors.size());
        AdminService.ProcessError e = errors.get(0);
        assertEquals(p, e.processId());
        assertEquals(AdminService.ErrorKind.UNKNOWN, e.kind());
        assertEquals("/T/INCERTO", e.handler());
        assertEquals("Approvazione responsabile", e.taskText());
        assertNotNull(e.executionId());

        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> admin().resolveUnknown(VERDI, e.executionId(), true, " ")).code(), "la nota e' obbligatoria");
        Requests.Outcome r = admin().resolveUnknown(VERDI, e.executionId(), true, "verificato in ME23N: rilasciato");
        assertEquals(Requests.Outcome.Result.DONE, r.result());
        assertTrue(admin().processesInError(VERDI).isEmpty());
        assertEquals("RUNNING", value("SELECT status FROM ewf_core.process_instance WHERE id = " + p));
        assertEquals("30", value("SELECT max(step_no)::text FROM ewf_core.task_instance WHERE process_id = " + p),
                "confermato l'esito, il processo e' passato allo step successivo");
    }

    // ------------------------------------------------------------------ login

    @Test
    public void loginPasswordProvvisoriaCambioEBlocco() {
        assertEquals(Code.AUTH_FAILED, assertThrows(ServiceException.class,
                () -> auth.login("ROSSI", "qualsiasi1".toCharArray())).code(), "senza password impostata non si entra");
        String tmp = auth.resetPassword(ROSSI);
        AuthService.Session s = auth.login("rossi", tmp.toCharArray());
        assertEquals(ROSSI, s.userId());
        assertTrue(s.mustChangePassword(), "la provvisoria va cambiata");
        assertEquals("MROSSI", s.sapUser());

        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> auth.changePassword(ROSSI, tmp.toCharArray(), "corta1".toCharArray())).code());
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> auth.changePassword(ROSSI, tmp.toCharArray(), "solamentelettere".toCharArray())).code());
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> auth.changePassword(ROSSI, tmp.toCharArray(), "Rossi2026abc".toCharArray())).code(),
                "niente nome utente nella password");
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> auth.changePassword(ROSSI, "sbagliata99".toCharArray(), "Primavera2026".toCharArray())).code());
        auth.changePassword(ROSSI, tmp.toCharArray(), "Primavera2026".toCharArray());
        assertFalse(auth.login("ROSSI", "Primavera2026".toCharArray()).mustChangePassword());

        for (int i = 0; i < AuthService.MAX_FAILURES; i++)
            assertThrows(ServiceException.class, () -> auth.login("ROSSI", "Sbagliata2026".toCharArray()));
        ServiceException locked = assertThrows(ServiceException.class, () -> auth.login("ROSSI", "Primavera2026".toCharArray()));
        assertEquals(Code.AUTH_FAILED, locked.code(), "bloccato anche con la password giusta");
        assertEquals(assertThrows(ServiceException.class, () -> auth.login("NESSUNO", "x1".toCharArray())).getMessage(),
                locked.getMessage(), "stesso messaggio: non si capisce se l'utente esiste o e' bloccato");
        clock.now = clock.now.plus(AuthService.LOCK_TIME).plusSeconds(1);
        assertEquals(ROSSI, auth.login("ROSSI", "Primavera2026".toCharArray()).userId(), "il blocco scade");
    }

    @Test
    public void utenteDisattivatoNonEntra() {
        String tmp = auth.resetPassword(NERI);
        new JdbcOrgWriter(db).setActive(NERI, false);
        assertEquals(Code.AUTH_FAILED, assertThrows(ServiceException.class, () -> auth.login("NERI", tmp.toCharArray())).code());
    }

    // ------------------------------------------------------------------ lista

    @Test
    public void listaConRiepilogoSemaforoFiltriEColonne() {
        long p = start("4500000001");
        WorkItem w = onlyTask(ROSSI);
        assertEquals(p, w.processId());
        assertEquals(Reason.OWN, w.reason());
        assertEquals("100123 Acme Spa - 1000", w.summary(), "segnaposto senza valore e separatore orfano ripuliti");
        assertEquals("1.500,50", w.attributes().get("value"));
        assertEquals("Mario Rossi", w.starter());
        assertTrue(w.webActionable());

        setTaskAge(w.taskId(), 5);
        assertEquals(Light.GREEN, onlyTask(ROSSI).light());
        setTaskAge(w.taskId(), 6);
        assertEquals(Light.YELLOW, onlyTask(ROSSI).light());
        assertEquals(6, onlyTask(ROSSI).daysWaiting());
        setTaskAge(w.taskId(), 11);
        assertEquals(Light.RED, onlyTask(ROSSI).light());
        sql("UPDATE ewf_core.process_type SET sla_yellow_days = 15, sla_red_days = 30");
        assertEquals(Light.GREEN, onlyTask(ROSSI).light(), "soglie per tipo di processo");

        Worklist all = svc.worklist(ROSSI, WorklistFilter.all());
        assertEquals(List.of(new TypeCount(ODA, "Approvazione OdA", 1)), all.typeCounts());
        assertEquals(1, svc.worklist(ROSSI, new WorklistFilter(null, "acme")).items().size());
        assertEquals(0, svc.worklist(ROSSI, new WorklistFilter(null, "globex")).items().size());
        assertEquals(0, svc.worklist(ROSSI, new WorklistFilter("ALTRO", null)).items().size());
        assertEquals(1, svc.worklist(ROSSI, new WorklistFilter("ALTRO", null)).typeCounts().size(),
                "i conteggi per il filtro restano sulla lista completa");
        assertTrue(svc.worklist(VERDI, WorklistFilter.all()).items().isEmpty());

        assertEquals(List.of(new Column("Fornitore", "supplier_name"), new Column("Divisione", "plant")), svc.columns(ODA));
    }

    @Test
    public void sostitutoVedeEFirmaPerContoDelTitolare() {
        long p = atApproval("4500000002");
        new JdbcOrgWriter(db).substitution(BIANCHI, NERI, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        WorkItem w = onlyTask(NERI);
        assertEquals(Reason.SUBSTITUTE, w.reason());
        assertEquals("Anna Bianchi", w.forWhom());

        ProcessDetail d = svc.detail(NERI, p);
        assertEquals(Reason.SUBSTITUTE, d.myTask().reason());
        svc.approve(NERI, w.taskId(), "ok per ferie", d.myTask().rowVersion());
        HistoryEntry h = svc.detail(ROSSI, p).history().stream()
                .filter(x -> x.stepNo() == 20).findFirst().orElseThrow();
        assertEquals("Sara Neri", h.actedBy());
        assertEquals("Anna Bianchi", h.forWhom());
        assertEquals("ok per ferie", h.note());
    }

    // ------------------------------------------------------------------ dettaglio e azioni

    @Test
    public void dettaglioVisibilitaPercorsoEAzioni() {
        long p = atApproval("4500000003");
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.detail(NERI, p)).code());
        assertEquals(Code.NOT_FOUND, assertThrows(ServiceException.class, () -> svc.detail(ROSSI, 1)).code());

        ProcessDetail d = svc.detail(BIANCHI, p);
        assertEquals("4500000003", d.header().documentKey());
        assertEquals("Acme Spa", d.header().attributes().get("supplier_name"));
        assertEquals(List.of(Requests.PathEntry.Kind.DONE, Requests.PathEntry.Kind.CURRENT, Requests.PathEntry.Kind.PLANNED),
                d.path().stream().map(Requests.PathEntry::kind).toList());
        assertEquals("Approvazione responsabile", d.myTask().taskText());
        assertEquals(List.of("SOLLECITO", "ERRORE"), d.myTask().functions().stream().map(Function::code).toList());
        assertEquals("Inviare il sollecito?", d.myTask().functions().get(0).confirmText());
        assertNull(svc.detail(ROSSI, p).myTask(), "il richiedente vede il processo ma non ha task");

        long t = d.myTask().taskId();
        assertEquals(Code.INVALID, assertThrows(ServiceException.class, () -> svc.reject(BIANCHI, t, "  ", null)).code(),
                "il rifiuto vuole la motivazione");
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.approve(VERDI, t, null, null)).code());
        assertEquals(Code.INVALID_STATE, assertThrows(ServiceException.class,
                () -> svc.approve(BIANCHI, t, null, d.myTask().rowVersion() + 1)).code(), "versione superata: niente sovrascritture");

        svc.approve(BIANCHI, t, null, d.myTask().rowVersion());
        WorkItem sap = onlyTask(ROSSI);
        assertFalse(sap.webActionable(), "l'ultimo step si chiude in SAP GUI");
        assertEquals(Code.NOT_SUPPORTED, assertThrows(ServiceException.class, () -> svc.approve(ROSSI, sap.taskId(), null, null)).code());
    }

    @Test
    public void rifiutoESpostamento() {
        long p = atApproval("4500000004");
        long t = onlyTask(BIANCHI).taskId();
        assertEquals(List.of("Luca Verdi"), svc.findUsers("ver", 10).stream().map(UserChoice::displayName).toList());
        long moved = svc.move(BIANCHI, t, VERDI, "competenza tua");
        assertEquals(moved, onlyTask(VERDI).taskId());
        assertTrue(svc.worklist(BIANCHI, WorklistFilter.all()).items().isEmpty());
        assertTrue(svc.canView(BIANCHI, p), "chi ha spostato il task continua a vedere il processo");

        svc.reject(VERDI, moved, "prezzo fuori listino", null);
        assertEquals(1, svc.worklist(ROSSI, WorklistFilter.all()).items().size(), "torna al richiedente");
        HistoryEntry ko = svc.detail(ROSSI, p).history().stream().filter(h -> "KO".equals(h.status())).findFirst().orElseThrow();
        assertEquals("prezzo fuori listino", ko.note());
        assertEquals("Luca Verdi", ko.actedBy());
    }

    // ------------------------------------------------------------------ commenti

    @Test
    public void commentiSoloAggiunteEVisibilita() {
        long p = atApproval("4500000005");
        assertEquals(Code.INVALID, assertThrows(ServiceException.class, () -> svc.addComment(ROSSI, p, " ")).code());
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.addComment(NERI, p, "ciao")).code());
        svc.addComment(ROSSI, p, "Urgente: consegna entro venerdi'");
        svc.addComment(BIANCHI, p, "Verifico con il fornitore");
        List<Comment> cs = svc.detail(BIANCHI, p).comments();
        assertEquals(List.of("Mario Rossi", "Anna Bianchi"), cs.stream().map(Comment::author).toList());
        assertEquals(List.of(false, true), cs.stream().map(Comment::mine).toList());
        EwfService small = service(new EwfService.Config(1024, 10), EwfService.Backends.none());
        assertEquals(Code.INVALID, assertThrows(ServiceException.class, () -> small.addComment(ROSSI, p, "troppo lungo davvero")).code());
    }

    // ------------------------------------------------------------------ allegati

    @Test
    public void allegatiDiProcesso() {
        long p = atApproval("4500000006");
        byte[] pdf = "%PDF-1.4 preventivo".getBytes(StandardCharsets.UTF_8);
        Attachment a = svc.addAttachment(ROSSI, p, "C:\\Documenti\\..\\preventivo.pdf", "application/pdf", pdf);
        assertEquals("preventivo.pdf", a.fileName(), "niente percorsi nel nome");
        assertEquals(List.of(a.id()), svc.attachments(BIANCHI, p).stream().map(Attachment::id).toList());
        assertFalse(svc.attachments(BIANCHI, p).get(0).deletable(), "non e' suo");
        assertTrue(svc.attachments(ROSSI, p).get(0).deletable());
        assertArrayEquals(pdf, svc.attachmentContent(BIANCHI, p, a.id()).content());
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.attachments(NERI, p)).code());
        assertEquals(Code.NOT_FOUND, assertThrows(ServiceException.class, () -> svc.attachmentContent(BIANCHI, p, "P:999")).code());

        long other = start("4500000099");
        assertEquals(Code.NOT_FOUND, assertThrows(ServiceException.class, () -> svc.attachmentContent(ROSSI, other, a.id())).code(),
                "un allegato si legge solo dal suo processo");

        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.deleteAttachment(BIANCHI, p, a.id())).code());
        svc.deleteAttachment(ROSSI, p, a.id());
        assertTrue(svc.attachments(ROSSI, p).isEmpty());

        EwfService small = service(new EwfService.Config(10, 4000), EwfService.Backends.none());
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> small.addAttachment(ROSSI, p, "grande.bin", null, new byte[11])).code());
        assertEquals(Code.INVALID, assertThrows(ServiceException.class,
                () -> svc.addAttachment(ROSSI, p, "vuoto.txt", null, new byte[0])).code());

        svc.approve(BIANCHI, onlyTask(BIANCHI).taskId(), null, null);
        sql("UPDATE ewf_core.process_instance SET status = 'COMPLETED' WHERE id = " + p);
        assertEquals(Code.INVALID_STATE, assertThrows(ServiceException.class,
                () -> svc.addAttachment(ROSSI, p, "tardi.pdf", "application/pdf", pdf)).code());
    }

    @Test
    public void allegatiDelDocumentoNelSistemaDiOrigine() {
        long p = atApproval("4500000007");
        sql("UPDATE ewf_core.process_type SET attachment_scope = 'DOCUMENT'");
        docAttachments.items.add(new DocumentAttachments.Item("GOS1", "offerta.pdf", "application/pdf", 3L, "MROSSI", clock.now));
        docAttachments.content.put("GOS1", new byte[]{1, 2, 3});

        assertTrue(svc.detail(BIANCHI, p).links().documentAttachments());
        List<Attachment> list = svc.attachments(BIANCHI, p);
        assertEquals("D:GOS1", list.get(0).id());
        assertArrayEquals(new byte[]{1, 2, 3}, svc.attachmentContent(BIANCHI, p, "D:GOS1").content());

        Attachment added = svc.addAttachment(BIANCHI, p, "nota.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("D:GOS2", added.id());
        assertEquals("Anna Bianchi", docAttachments.lastUploader, "senza utente SAP passa il nome");
        assertEquals(Code.NOT_FOUND, assertThrows(ServiceException.class, () -> svc.deleteAttachment(BIANCHI, p, "D:GOS2")).code(),
                "gli allegati del documento non si eliminano da eWF");

        docAttachments.down = true;
        ServiceException e = assertThrows(ServiceException.class, () -> svc.attachments(BIANCHI, p));
        assertEquals(Code.BACKEND, e.code());
        assertTrue(e.getMessage().contains("RFC_COMMUNICATION_FAILURE"));

        EwfService noBackend = service(EwfService.Config.defaults(), EwfService.Backends.none());
        assertEquals(Code.NOT_SUPPORTED, assertThrows(ServiceException.class, () -> noBackend.attachments(BIANCHI, p)).code());
    }

    // ------------------------------------------------------------------ PDF, SAP GUI, funzioni

    @Test
    public void schedePdfECollegamentoSapGui() {
        long p = atApproval("4500000008");
        svc.addComment(ROSSI, p, "Urgente");
        Links forBianchi = svc.detail(BIANCHI, p).links();
        assertEquals(List.of("ODA", "EWF"), forBianchi.sheets().stream().map(SheetView::code).toList(),
                "la vista del sistema di origine e il riepilogo eWF, sempre presente");
        assertFalse(forBianchi.sapGui(), "BIANCHI non ha utente SAP");
        assertTrue(svc.detail(ROSSI, p).links().sapGui());

        FileContent pdf = svc.documentSheet(BIANCHI, p, "ODA");
        assertEquals("application/pdf", pdf.mimeType());
        assertEquals("F_4500000008_ODA.pdf", pdf.fileName());
        assertTrue(new String(pdf.content(), 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF-"));
        assertEquals(1, sheets.built, "la scheda chiede i dati al sistema di origine");
        FileContent ewf = svc.documentSheet(BIANCHI, p, "EWF");
        assertTrue(ewf.content().length > 1000);
        assertEquals(1, sheets.built, "il riepilogo eWF non chiama il sistema di origine");
        assertEquals(Code.NOT_SUPPORTED, assertThrows(ServiceException.class, () -> svc.documentSheet(BIANCHI, p, "ZZZ")).code());
        sheets.down = true;
        assertEquals(Code.BACKEND, assertThrows(ServiceException.class, () -> svc.documentSheet(BIANCHI, p, "ODA")).code());
        assertEquals(1, svc.documentSheet(BIANCHI, p, "EWF").content().length > 0 ? 1 : 0, "il riepilogo funziona anche col sistema giu'");
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.documentSheet(NERI, p, "EWF")).code());
        assertEquals(Code.NOT_SUPPORTED, assertThrows(ServiceException.class, () -> svc.sapGuiShortcut(BIANCHI, p)).code());
        FileContent sc = svc.sapGuiShortcut(ROSSI, p);
        assertTrue(new String(sc.content(), StandardCharsets.UTF_8).contains("user=MROSSI"));
    }

    @Test
    public void funzioniSpecialiDelTask() {
        atApproval("4500000009");
        long t = onlyTask(BIANCHI).taskId();
        FunctionResult r = svc.runFunction(BIANCHI, t, "SOLLECITO");
        assertTrue(r.ok());
        assertEquals("Sollecito inviato per 4500000009 da BIANCHI", r.message());
        ServiceException ko = assertThrows(ServiceException.class, () -> svc.runFunction(BIANCHI, t, "ERRORE"));
        assertEquals(Code.FUNCTION_FAILED, ko.code());
        assertEquals("Fornitore senza indirizzo mail", ko.getMessage());
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(ServiceException.class, () -> svc.runFunction(ROSSI, t, "SOLLECITO")).code());
        assertEquals(Code.NOT_FOUND, assertThrows(ServiceException.class, () -> svc.runFunction(BIANCHI, t, "ZZZ")).code());
        handlers.remove("/T/FN_OK");
        assertEquals(Code.NOT_SUPPORTED, assertThrows(ServiceException.class, () -> svc.runFunction(BIANCHI, t, "SOLLECITO")).code());
        assertEquals(1, svc.worklist(BIANCHI, WorklistFilter.all()).items().size(), "le funzioni non chiudono il task");
    }

    // ------------------------------------------------------------------ supporto

    void setTaskAge(long taskId, int days) {
        sql("UPDATE ewf_core.task_instance SET created_at = timestamptz '" + clock.now.minus(Duration.ofDays(days)) + "' WHERE id = " + taskId);
    }

    void sql(String s) {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate(s);
            }
        });
    }

    String value(String query) {
        return db.with(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(query)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    private void truncate() {
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

    static final class FakeDocAttachments implements DocumentAttachments {
        final List<Item> items = new ArrayList<>();
        final Map<String, byte[]> content = new HashMap<>();
        boolean down;
        String lastUploader;

        @Override public boolean supports(DocRef doc) { return "F".equals(doc.docType()); }
        @Override public List<Item> list(DocRef doc) {
            if (down) throw new IllegalStateException("RFC_COMMUNICATION_FAILURE");
            return List.copyOf(items);
        }
        @Override public byte[] read(DocRef doc, String id) { return content.get(id); }
        @Override public String add(DocRef doc, String fileName, String mimeType, byte[] c, String sapUser, String uploaderName) {
            String id = "GOS" + (items.size() + 1);
            lastUploader = sapUser != null ? sapUser : uploaderName;
            items.add(new Item(id, fileName, mimeType, (long) c.length, lastUploader, Instant.now()));
            content.put(id, c);
            return id;
        }
    }

    static final class FakeSheets implements DocumentSheets {
        int built;
        boolean down;
        @Override public List<View> views(DocRef doc) {
            return "F".equals(doc.docType()) ? List.of(new View("ODA", "Scheda OdA")) : List.of();
        }
        @Override public DocumentSheet build(DocRef doc, String viewCode, Map<String, String> context) {
            assertEquals("Acme Spa", context.get("supplier_name"), "gli attributi salvati arrivano alla vista");
            if (down) throw new IllegalStateException("RFC_COMMUNICATION_FAILURE");
            built++;
            return new DocumentSheet("Ordine d'acquisto " + doc.documentKey(), null, "OdA " + doc.documentKey(),
                    List.of(new DocumentSheet.Fields("Ordine", 2, List.of(DocumentSheet.Field.of("Tipo", "ZNB")))));
        }
    }

    static final class FakeSapLink implements SapGuiLink {
        @Override public boolean supports(DocRef doc) { return true; }
        @Override public Shortcut shortcut(DocRef doc, String sapUser) {
            return new Shortcut(doc.documentKey() + ".sap",
                    ("[System]\n[User]\nuser=" + sapUser + "\n[Function]\nCommand=*/EWF/VIEWDOC P_DOC=" + doc.documentKey())
                            .getBytes(StandardCharsets.UTF_8), "application/x-sapshortcut");
        }
    }
}
