package it.eone.ewf.engine;

import it.eone.ewf.engine.EngineException.Code;
import it.eone.ewf.engine.Requests.Outcome;
import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.HandlerResult;
import it.eone.ewf.engine.spi.HandlerResult.UserRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static it.eone.ewf.engine.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenari del motore. Le sottoclassi decidono dove girano: in memoria o su PostgreSQL.
 */
abstract class WorkflowEngineContract {

    protected Fixture f;

    protected abstract Backend newBackend();

    @BeforeEach
    void setUp() {
        f = new Fixture(newBackend());
    }

    // ------------------------------------------------------------------
    // Percorso di approvazione e soglie d'importo (ex Z_WF_LIMIT)
    // ------------------------------------------------------------------

    @Test
    void ordinePiccoloSaltaIRilasciSuperioriEChiudeConRilascio() {
        ProcessInstance p = f.startOda("4500000001", "5000");
        TaskInstance t10 = f.openTask(p.id);
        assertEquals(10, t10.stepNo);
        assertEquals(ROSSI, t10.assigneeId);

        f.ok(t10.id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);
        assertEquals(20, t20.stepNo);
        assertEquals(BIANCHI, t20.assigneeId);
        assertEquals(ROSSI, t20.fromUserId);

        Outcome o = f.ok(t20.id, BIANCHI);
        assertEquals(Outcome.Result.DONE, o.result());
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
        assertEquals(List.of("4500000001"), f.released);
        assertEquals(2, f.b.auditTypes(p.id).stream().filter("STEP_SKIPPED"::equals).count());
        assertEquals("01", f.releaseParams.get(0).get("Z_WF_SET_RIL"), "codice di rilascio all'azione di fine");
    }

    @Test
    void ordineGrandePassaPerTuttiILivelli() {
        ProcessInstance p = f.startOda("4500000002", "60000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        TaskInstance t30 = f.openTask(p.id);
        assertEquals(VERDI, t30.assigneeId);
        f.ok(t30.id, VERDI);
        TaskInstance t40 = f.openTask(p.id);
        assertEquals(NERI, t40.assigneeId);
        f.ok(t40.id, NERI);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
    }

    @Test
    void documentoLettoSoloAllAvvio() {
        f.purchaseOrders.context("4500000003", Map.of("plant", "1000"));
        ProcessInstance p = f.startOda("4500000003", "5000");
        int reads = f.purchaseOrders.reads();
        // nel frattempo l'OdA viene modificato in SAP: il processo resta sulla fotografia dell'avvio
        f.purchaseOrders.value("4500000003", Money.of("15000", "EUR"));
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id), "5000 EUR: soglie del 2° e 3° livello non raggiunte");
        assertEquals(reads, f.purchaseOrders.reads(), "nessuna rilettura dopo l'avvio");
        assertEquals(Map.of("plant", "1000"), f.store.process(p.id).orElseThrow().docContext, "contesto salvato sul processo");
    }

    @Test
    void documentoInesistenteAvvioRifiutato() {
        f.purchaseOrders.missing("4500000099");
        EngineException e = assertThrows(EngineException.class, () -> f.startOda("4500000099", "100"));
        assertEquals(Code.DOCUMENT_NOT_FOUND, e.code());
        assertTrue(e.getMessage().contains("cancellato"), e.getMessage());
    }

    @Test
    void sistemaDOrigineIrraggiungibileAvvioRifiutatoENessunProcesso() {
        f.purchaseOrders.offline(true);
        EngineException e = assertThrows(EngineException.class, () -> f.engine.start(
                new Requests.Start(ODA, "4500000098", null, ROSSI, null)));
        assertEquals(Code.BACKEND_UNAVAILABLE, e.code());
        f.purchaseOrders.offline(false);
        ProcessInstance p = f.engine.start(new Requests.Start(ODA, "4500000098", null, ROSSI, null));
        assertEquals(ProcessStatus.RUNNING, p.status, "nessun processo fantasma rimasto dal tentativo fallito");
    }

    @Test
    void riavvioConSistemaIrraggiungibileLasciaIlProcessoComEra() {
        ProcessInstance p = f.startOda("4500000097", "1000");
        f.purchaseOrders.offline(true);
        assertEquals(Code.BACKEND_UNAVAILABLE,
                assertThrows(EngineException.class, () -> f.engine.restart(p.id, ADMIN, "Prova")).code());
        assertEquals(ProcessStatus.RUNNING, f.status(p.id), "non annullato");
        f.purchaseOrders.offline(false);
    }

    @Test
    void sogliaConfrontataNellaDivisaDelloStep() {
        // 12.000 USD * 0,90 = 10.800 EUR >= 10.000 => serve il 2° livello
        f.purchaseOrders.value("4500000004", Money.of("12000", "USD"));
        ProcessInstance p = f.engine.start(new Requests.Start(ODA, "4500000004", null, ROSSI, null));
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        assertEquals(30, f.openTask(p.id).stepNo);
    }

    // ------------------------------------------------------------------
    // Rifiuto
    // ------------------------------------------------------------------

    @Test
    void rifiutoTornaAlRichiedente() {
        ProcessInstance p = f.startOda("4500000010", "20000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        TaskInstance t30 = f.openTask(p.id);
        f.ko(t30.id, VERDI, "Prezzo fuori listino");

        TaskInstance back = f.openTask(p.id);
        assertEquals(10, back.stepNo);
        assertEquals(ROSSI, back.assigneeId);
        assertEquals(VERDI, back.fromUserId);
        assertEquals(TaskStatus.KO, f.store.task(t30.id).orElseThrow().status);
        assertEquals("Prezzo fuori listino", f.store.task(t30.id).orElseThrow().note);
        assertEquals(ProcessStatus.RUNNING, f.status(p.id));
    }

    @Test
    void rifiutoSenzaStepKoChiudeIlProcesso() {
        ProcessInstance p = f.startOda("4500000011", "1000");
        f.ko(f.openTask(p.id).id, ROSSI, "Annullo la richiesta");
        assertEquals(ProcessStatus.REJECTED, f.status(p.id));
        assertTrue(f.released.isEmpty(), "nessun rilascio su un processo rifiutato");
    }

    // ------------------------------------------------------------------
    // Autorizzazioni: titolare, peso, sostituto, team
    // ------------------------------------------------------------------

    @Test
    void soloIlTitolarePuoAgire() {
        ProcessInstance p = f.startOda("4500000020", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);
        EngineException e = assertThrows(EngineException.class, () -> f.ok(t20.id, VERDI));
        assertEquals(Code.NOT_AUTHORIZED, e.code());
        assertEquals(TaskStatus.OPEN, f.store.task(t20.id).orElseThrow().status);
    }

    @Test
    void pesoPerProcessoPrevaleSuQuelloDellUtente() {
        f.b.processWeight(VERDI, ODA, 150);   // ex USR_AUTH.LIV_AUT, sotto i 200 richiesti
        ProcessInstance p = f.startOda("4500000021", "20000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        TaskInstance t30 = f.openTask(p.id);
        EngineException e = assertThrows(EngineException.class, () -> f.ok(t30.id, VERDI));
        assertEquals(Code.WEIGHT_TOO_LOW, e.code());
    }

    @Test
    void sostitutoAgisceEResteTraccia() {
        f.b.substitution(BIANCHI, GIALLI, TODAY.minusDays(1), TODAY.plusDays(10));
        ProcessInstance p = f.startOda("4500000022", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);

        assertTrue(f.engine.inbox(GIALLI).stream().anyMatch(i ->
                i.taskId() == t20.id && i.reason() == Requests.InboxEntry.Reason.SUBSTITUTE));
        f.ok(t20.id, GIALLI);
        TaskInstance done = f.store.task(t20.id).orElseThrow();
        assertEquals(GIALLI, done.actedById);
        assertTrue(done.actedAsSubstitute);
        assertEquals(BIANCHI, done.assigneeId);
    }

    @Test
    void taskDiTeamConPresaInCaricoEPeso() {
        TaskDef teamTask = new TaskDef(901, 0, "Verifica allegati", "VERIFICA", 100, AssigneeMode.RULES, null,
                true, "ACQ", PreviewMode.ROLE, Interaction.WEB, null, List.of());
        f.b.addRule(rule(10, "VERIFICA", null, ACQ1, null));
        f.b.defineType(new ProcessType("UC_MULTE", "Multe", "F", false, false, false));
        f.b.activate(new ProcessVersion(9, "UC_MULTE", 1, null, List.of(
                new StepDef(10, true, JoinPolicy.ALL, null, null, null, List.of(teamTask)))));
        f.purchaseOrders.value("M1", Money.of("100", "EUR"));
        ProcessInstance p = f.engine.start(new Requests.Start("UC_MULTE", "M1", null, ROSSI, null));
        TaskInstance t = f.openTask(p.id);
        assertEquals(ACQ1, t.assigneeId);
        assertEquals("ACQ", t.teamCode);
        assertTrue(f.engine.inbox(ACQ2).stream().anyMatch(i -> i.reason() == Requests.InboxEntry.Reason.TEAM));

        // ACQ2 e' nel team ma ha peso 50 < 100
        assertEquals(Code.WEIGHT_TOO_LOW, assertThrows(EngineException.class, () -> f.ok(t.id, ACQ2)).code());

        f.engine.claim(t.id, ACQ1);
        f.b.processWeight(ACQ2, "UC_MULTE", 100);
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(EngineException.class, () -> f.ok(t.id, ACQ2)).code(),
                "in carico ad ACQ1");
        f.engine.release(t.id, ACQ1);
        f.ok(t.id, ACQ2);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
    }

    // ------------------------------------------------------------------
    // Handler: blocchi, errori, esiti sconosciuti, istruzioni
    // ------------------------------------------------------------------

    @Test
    void controlloPrimaDellAzioneBloccaSeOdaInUso() {
        ProcessInstance p = f.startOda("4500000030", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);
        f.lockCheck = HandlerResult.ko("Ordine di Acquisto in uso - impossibile procedere");

        Outcome o = f.ok(t20.id, BIANCHI);
        assertEquals(Outcome.Result.REJECTED, o.result());
        assertEquals("Ordine di Acquisto in uso - impossibile procedere", o.message());
        assertEquals(TaskStatus.OPEN, f.store.task(t20.id).orElseThrow().status);

        f.lockCheck = HandlerResult.ok();
        assertEquals(Outcome.Result.DONE, f.ok(t20.id, BIANCHI).result());
    }

    @Test
    void rilascioFallitoNonChiudeIlProcessoESiPuoRitentare() {
        f.releaseFailures = 1;
        ProcessInstance p = f.startOda("4500000031", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);

        ProcessInstance err = f.store.process(p.id).orElseThrow();
        assertEquals(ProcessStatus.ERROR, err.status);
        assertTrue(err.statusReason.startsWith(WorkflowEngine.REASON_END_FAILED));
        assertTrue(f.released.isEmpty());

        f.engine.retryCompletion(p.id, ADMIN);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
        assertEquals(List.of("4500000031"), f.released);
    }

    @Test
    void esitoSconosciutoBloccaFinoAConfermaManuale() {
        f.handlers.register("Z_NOTA_CREDITO", ctx -> { throw new RuntimeException("timeout RFC"); });
        f.b.defineType(new ProcessType("RESI", "Resi", "F", false, false, false));
        f.b.activate(new ProcessVersion(7, "RESI", 1, null, List.of(
                new StepDef(10, true, JoinPolicy.ALL, 20, null, null,
                        List.of(task(701, "Richiesta", "UFFICIO_ACQUISTI", 0, AssigneeMode.STARTER))),
                new StepDef(20, false, JoinPolicy.ALL, null, 10, null,
                        List.of(task(702, "Crea nota di credito", "RESPONSABILE_ACQ", 100, AssigneeMode.RULES,
                                HookDef.of(HookEvent.ON_OK, "Z_NOTA_CREDITO")))))));
        f.purchaseOrders.value("R1", Money.of("300", "EUR"));
        ProcessInstance p = f.engine.start(new Requests.Start("RESI", "R1", null, ROSSI, null));
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);

        Outcome o = f.ok(t20.id, BIANCHI);
        assertEquals(Outcome.Result.UNKNOWN, o.result());
        assertEquals(ProcessStatus.ERROR, f.status(p.id));
        assertEquals(TaskStatus.EXECUTING, f.store.task(t20.id).orElseThrow().status);

        HandlerExecution unknown = f.store.executionsOf(p.id).stream()
                .filter(x -> x.status == HandlerExecution.Status.UNKNOWN).findFirst().orElseThrow();
        f.engine.resolveUnknown(unknown.id, true, ADMIN, "Nota di credito 90001234 trovata in VF03");
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
        assertEquals(TaskStatus.OK, f.store.task(t20.id).orElseThrow().status);
    }

    @Test
    void handlerPuoChiedereDiSaltareUnoStep() {
        f.handlers.register("Z_SKIP_L1", ctx -> HandlerResult.withDirectives(new HandlerResult.SkipStep(30)));
        f.b.activate(withOkHookOnStep20(f.odaVersion(2, PreviewMode.NAME), "Z_SKIP_L1"));
        ProcessInstance p = f.startOda("4500000040", "20000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id), "step 30 saltato su istruzione, 40 sotto soglia");
    }

    @Test
    void documentiCreatiDagliHandlerVengonoRegistrati() {
        f.handlers.register("Z_RESO", ctx -> HandlerResult.withDocuments(
                new HandlerResult.DocRef("H", "60001234", null, null)));
        f.b.activate(withOkHookOnStep20(f.odaVersion(3, PreviewMode.NAME), "Z_RESO"));
        ProcessInstance p = f.startOda("4500000041", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.ok(f.openTask(p.id).id, BIANCHI);
        assertEquals("60001234", f.store.documentsOf(p.id).get(0).docNumber());
    }

    // ------------------------------------------------------------------
    // Paralleli generati dall'assegnazione (ex TB_USERS -> SUBOP)
    // ------------------------------------------------------------------

    protected ProcessInstance startContestazione() {
        f.handlers.register("Z_USER_CONTESTAZIONE", ctx -> HandlerResult.users(
                UserRef.ewf("VERDI"), UserRef.sap("SNERI"), UserRef.sap("NONESISTE")));
        TaskDef parallel = new TaskDef(801, 0, "Parere contestazione", "PARERE", 100, AssigneeMode.HANDLER,
                "Z_USER_CONTESTAZIONE", false, null, PreviewMode.NAME, Interaction.WEB, null, List.of());
        f.b.defineType(new ProcessType("CONTESTAZIONE", "Contestazione", "F", false, false, false));
        f.b.activate(new ProcessVersion(8, "CONTESTAZIONE", 1, null, List.of(
                new StepDef(10, true, JoinPolicy.ALL, 20, null, null,
                        List.of(task(800, "Apertura", "UFFICIO_ACQUISTI", 0, AssigneeMode.STARTER))),
                new StepDef(20, false, JoinPolicy.ALL, null, 10, null, List.of(parallel)))));
        f.purchaseOrders.value("C1", Money.of("100", "EUR"));
        ProcessInstance p = f.engine.start(new Requests.Start("CONTESTAZIONE", "C1", null, ROSSI, null));
        f.ok(f.openTask(p.id).id, ROSSI);
        return p;
    }

    @Test
    void paralleliTuttiDevonoApprovare() {
        ProcessInstance p = startContestazione();
        List<TaskInstance> par = f.openTasks(p.id);
        assertEquals(2, par.size(), "VERDI (login) e NERI (utente SAP mappato); NONESISTE scartato");
        assertTrue(f.b.auditTypes(p.id).contains("ASSIGNEE_NOT_MAPPED"));

        f.ok(par.get(0).id, par.get(0).assigneeId);
        assertEquals(ProcessStatus.RUNNING, f.status(p.id), "aspetta l'altro parallelo");
        f.ok(par.get(1).id, par.get(1).assigneeId);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
    }

    @Test
    void parallelUnSoloRifiutoChiudeLoStep() {
        ProcessInstance p = startContestazione();
        List<TaskInstance> par = f.openTasks(p.id);
        f.ok(par.get(0).id, par.get(0).assigneeId);
        f.ko(par.get(1).id, par.get(1).assigneeId, "Non concordo");
        TaskInstance back = f.openTask(p.id);
        assertEquals(10, back.stepNo);
    }

    // ------------------------------------------------------------------
    // Assegnazione: specificita' delle regole, riserva
    // ------------------------------------------------------------------

    @Test
    void vinceLaRegolaPiuSpecifica() {
        f.b.addRule(rule(20, "RESPONSABILE_ACQ", "1000", GIALLI, null));   // solo divisione 1000
        f.purchaseOrders.context("4500000050", Map.of("plant", "1000"));
        ProcessInstance p = f.startOda("4500000050", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        assertEquals(GIALLI, f.openTask(p.id).assigneeId);

        f.purchaseOrders.context("4500000051", Map.of("plant", "2000"));
        ProcessInstance p2 = f.startOda("4500000051", "1000");
        f.ok(f.openTask(p2.id).id, ROSSI);
        assertEquals(BIANCHI, f.openTask(p2.id).assigneeId);
    }

    @Test
    void senzaRegolaIlTaskVaAlTeamDiRiserva() {
        f.b.removeRules("RESPONSABILE_ACQ");
        ProcessInstance p = f.startOda("4500000052", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t = f.openTask(p.id);
        assertNull(t.assigneeId);
        assertEquals("WFADM", t.teamCode);
        assertTrue(f.b.auditTypes(p.id).contains("ASSIGNEE_FALLBACK"));
        f.ok(t.id, ADMIN);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
    }

    @Test
    void utenteDisattivatoNonRiceveTask() {
        f.b.deactivate(BIANCHI);
        ProcessInstance p = f.startOda("4500000053", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        assertEquals("WFADM", f.openTask(p.id).teamCode);
    }

    // ------------------------------------------------------------------
    // Sposta, concorrenza, duplicati, riavvio, versioni, anteprima
    // ------------------------------------------------------------------

    @Test
    void spostaCreaUnNuovoTaskPerIlDestinatario() {
        ProcessInstance p = f.startOda("4500000060", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);
        long moved = f.engine.move(t20.id, BIANCHI, GIALLI, "Sono in ferie");
        assertEquals(TaskStatus.MOVED, f.store.task(t20.id).orElseThrow().status);
        TaskInstance n = f.store.task(moved).orElseThrow();
        assertEquals(GIALLI, n.assigneeId);
        assertEquals(BIANCHI, n.fromUserId);
        f.ok(moved, GIALLI);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
    }

    @Test
    void doppioClickVieneRespinto() {
        ProcessInstance p = f.startOda("4500000061", "1000");
        TaskInstance t = f.openTask(p.id);
        int seen = t.rowVersion;
        f.engine.claim(t.id, ROSSI);   // qualcuno modifica il task nel frattempo
        EngineException e = assertThrows(EngineException.class, () ->
                f.engine.act(new Requests.Act(t.id, ROSSI, Requests.Action.OK, null, seen)));
        assertEquals(Code.CONCURRENT_UPDATE, e.code());
    }

    @Test
    void unSoloProcessoApertoPerDocumento() {
        f.startOda("4500000062", "1000");
        EngineException e = assertThrows(EngineException.class, () -> f.startOda("4500000062", "1000"));
        assertEquals(Code.DUPLICATE_PROCESS, e.code());
    }

    @Test
    void ilProcessoRestaSullaVersioneConCuiEPartito() {
        ProcessInstance p = f.startOda("4500000063", "60000");
        // nuova versione: solo due step
        long v5 = f.b.activate(new ProcessVersion(5, ODA, 5, null, List.of(
                new StepDef(10, true, JoinPolicy.ALL, null, null, null,
                        List.of(task(501, "Unico step", "UFFICIO_ACQUISTI", 0, AssigneeMode.STARTER))))));
        f.ok(f.openTask(p.id).id, ROSSI);
        assertEquals(20, f.openTask(p.id).stepNo, "continua sulla versione 1");

        ProcessInstance n = f.engine.restart(p.id, ADMIN, "Adeguamento al nuovo percorso");
        assertEquals(ProcessStatus.CANCELLED, f.status(p.id));
        assertEquals(v5, n.versionId);
        assertEquals(1, f.store.links(p.id).size());
        assertEquals(ProcessLink.RESTART, f.store.links(p.id).get(0).linkType());
    }

    @Test
    void anteprimaRispettaLaVisibilita() {
        f.b.activate(f.odaVersion(6, PreviewMode.HIDDEN));
        ProcessInstance p = f.startOda("4500000064", "60000");
        List<Requests.PreviewEntry> prev = f.engine.preview(p.id);
        assertEquals(2, prev.size(), "step 20 e 30; il 40 e' nascosto");
        assertEquals("Anna Bianchi", prev.get(0).assignee());
        assertEquals("Luca Verdi", prev.get(1).assignee());
    }

    @Test
    void anteprimaNonMostraStepSottoSoglia() {
        ProcessInstance p = f.startOda("4500000065", "5000");
        assertEquals(1, f.engine.preview(p.id).size());
    }

    // ------------------------------------------------------------------

    private static ProcessVersion withOkHookOnStep20(ProcessVersion v, String handler) {
        List<StepDef> steps = new java.util.ArrayList<>();
        for (int n : new int[]{10, 20, 30, 40}) {
            StepDef s = v.step(n).orElseThrow();
            if (n == 20) {
                TaskDef t = s.tasks().get(0);
                List<HookDef> hooks = new java.util.ArrayList<>(t.hooks());
                hooks.add(HookDef.of(HookEvent.ON_OK, handler));
                t = new TaskDef(t.id(), t.subopNo(), t.text(), t.roleCode(), t.requiredWeight(), t.assigneeMode(),
                        t.assigneeHandlerCode(), t.teamTask(), t.teamCode(), t.previewMode(), t.interaction(),
                        t.taskParam(), hooks);
                s = new StepDef(s.stepNo(), s.first(), s.join(), s.nextOk(), s.nextKo(), s.minAmount(), List.of(t));
            }
            steps.add(s);
        }
        return new ProcessVersion(v.id(), v.processTypeCode(), v.versionNo(), v.endHandlerCode().orElse(null), steps);
    }

    // ------------------------------------------------------------------
    // Nodi di scelta e rami per opzione
    // ------------------------------------------------------------------

    private TaskInstance node(ProcessInstance p) {
        f.ok(f.openTask(p.id).id, ROSSI);                       // 10 -> 20
        TaskInstance n = f.openTask(p.id);
        assertEquals(20, n.stepNo);
        assertEquals(BIANCHI, n.assigneeId);
        return n;
    }

    @Test
    void ramoAPassaPerLaSuaVarianteEPoiPerIlTroncoComune() {
        ProcessInstance p = f.startRami("R-A");
        TaskInstance n = node(p);
        assertEquals(List.of("A", "B", "RIF"), f.engine.options(n.id).stream().map(Requests.OptionEntry::code).toList());

        f.choose(n.id, BIANCHI, "A");
        TaskInstance a = f.openTask(p.id);
        assertEquals(30, a.stepNo);
        assertEquals(Long.valueOf(VERDI), a.assigneeId, "variante A: Lavorazione A");
        assertEquals(Map.of(20, "A"), f.engine.choices(p.id));
        assertEquals("standard", f.optionHandlerCalls.get(0).get("motivo"), "handler dell'opzione con la sua configurazione");
        assertEquals("A", f.optionHandlerCalls.get(0).get(WorkflowEngine.PARAM_OPTION));

        f.ok(a.id, VERDI);
        TaskInstance c = f.openTask(p.id);
        assertEquals(50, c.stepNo, "A salta il 40 (esiste solo nel ramo B) e si ricongiunge al 50");
        f.ok(c.id, ROSSI);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
        assertEquals("20=A", f.endParams.get(0).get(WorkflowEngine.PARAM_OPTIONS), "le scelte arrivano all'azione di fine");
        assertEquals("X", f.endParams.get(0).get("FINE"));
        assertTrue(f.b.auditTypes(p.id).contains("OPTION_CHOSEN"));
    }

    @Test
    void ramoBHaUnoStepInPiu() {
        ProcessInstance p = f.startRami("R-B");
        f.choose(node(p).id, BIANCHI, "B");
        TaskInstance b30 = f.openTask(p.id);
        assertEquals(Long.valueOf(NERI), b30.assigneeId, "variante B: Lavorazione B");
        f.ok(b30.id, NERI);
        TaskInstance b40 = f.openTask(p.id);
        assertEquals(40, b40.stepNo);
        f.ok(b40.id, ROSSI);
        TaskInstance c = f.openTask(p.id);
        assertEquals(50, c.stepNo);
        f.ok(c.id, ROSSI);
        assertEquals(ProcessStatus.COMPLETED, f.status(p.id));
        assertEquals("20=B", f.traced.get(f.traced.size() - 1).get(WorkflowEngine.PARAM_OPTIONS),
                "gli handler degli step successivi vedono la scelta");
        assertTrue(f.optionHandlerCalls.isEmpty(), "B non ha handler");
    }

    @Test
    void rifiutoComeOpzioneChiudeInNegativo() {
        ProcessInstance p = f.startRami("R-RIF");
        f.choose(node(p).id, BIANCHI, "RIF");
        assertEquals(ProcessStatus.REJECTED, f.status(p.id));
        assertTrue(f.endParams.isEmpty(), "nessuna azione di fine su un rifiuto");
    }

    @Test
    void sulNodoServeUnOpzioneESoloUnaDiQuelleProposte() {
        ProcessInstance p = f.startRami("R-ERR");
        TaskInstance n = node(p);
        EngineException e = assertThrows(EngineException.class, () -> f.ok(n.id, BIANCHI));
        assertEquals(Code.INVALID_STATE, e.code());
        assertEquals(Code.INVALID_STATE, assertThrows(EngineException.class, () -> f.choose(n.id, BIANCHI, "ZZ")).code());
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(EngineException.class, () -> f.choose(n.id, ACQ2, "A")).code(),
                "la scelta segue le stesse regole di autorizzazione di Approva/Respingi");
        assertEquals(TaskStatus.OPEN, f.openTask(p.id).status, "nessun effetto");

        ProcessInstance o = f.startOda("4500000901", "1000");
        TaskInstance t10 = f.openTask(o.id);
        assertEquals(Code.INVALID_STATE, assertThrows(EngineException.class, () -> f.choose(t10.id, ROSSI, "A")).code(),
                "opzioni solo sui nodi di scelta");
    }

    @Test
    void ritornoAlNodoESceltaDiversaCambiaRamo() {
        ProcessInstance p = f.startRami("R-BACK");
        f.choose(node(p).id, BIANCHI, "B");
        f.ko(f.openTask(p.id).id, NERI, "rivedere");              // 30/B rifiuta: torna al nodo 20
        TaskInstance again = f.openTask(p.id);
        assertEquals(20, again.stepNo);
        f.choose(again.id, BIANCHI, "A");
        assertEquals(Map.of(20, "A"), f.engine.choices(p.id), "la nuova scelta sostituisce la precedente");
        assertEquals(Long.valueOf(VERDI), f.openTask(p.id).assigneeId, "ora la variante A");
    }

    @Test
    void handlerSceglieLOpzioneAlPostoDellUtente() {
        f.autoChoice = "B";
        ProcessInstance p = f.startRami("R-AUTO");
        f.ok(f.openTask(p.id).id, ROSSI);                        // 10 -> 20: il nodo si decide da solo
        TaskInstance t = f.openTask(p.id);
        assertEquals(30, t.stepNo);
        assertEquals(NERI, t.assigneeId);
        TaskInstance decided = f.store.tasksOf(p.id).stream().filter(x -> x.stepNo == 20).findFirst().orElseThrow();
        assertEquals("B", decided.optionCode);
        assertNull(decided.actedById, "scelta del sistema");
    }

    @Test
    void opzioneSconosciutaDaHandlerIgnorataIlNodoAspettaLUtente() {
        f.autoChoice = "ZZ";
        ProcessInstance p = f.startRami("R-AUTO2");
        f.ok(f.openTask(p.id).id, ROSSI);
        assertEquals(20, f.openTask(p.id).stepNo);
        assertTrue(f.b.auditTypes(p.id).contains("OPTION_SELECT_IGNORED"));
    }

    @Test
    void anteprimaSiFermaAlNodoNonAncoraDeciso() {
        ProcessInstance p = f.startRami("R-PREV");
        List<Requests.PreviewEntry> before = f.engine.preview(p.id);
        assertEquals(List.of(20), before.stream().map(Requests.PreviewEntry::stepNo).toList(),
                "dal 10 si vede solo il nodo: oltre dipende dall'opzione");
        f.choose(node(p).id, BIANCHI, "B");
        assertEquals(List.of(40, 50), f.engine.preview(p.id).stream().map(Requests.PreviewEntry::stepNo).toList(),
                "deciso B: il resto del ramo B e il tronco");
    }

    @Test
    void taskDelTeamDiRiservaSpostatoAUnaPersonaNonEPiuDelTeam() {
        f.b.removeRules("RESPONSABILE_ACQ");                     // lo step 20 finisce al team di riserva
        ProcessInstance p = f.startOda("4500000950", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        TaskInstance t20 = f.openTask(p.id);
        assertEquals("WFADM", t20.teamCode);

        long moved = f.engine.move(t20.id, ADMIN, BIANCHI, "A chi compete");
        TaskInstance n = f.store.task(moved).orElseThrow();
        assertEquals(Long.valueOf(BIANCHI), n.assigneeId);
        assertNull(n.teamCode, "dopo lo spostamento il task e' solo di BIANCHI");
        assertEquals(Code.NOT_AUTHORIZED, assertThrows(EngineException.class, () -> f.ok(moved, ADMIN)).code(),
                "l'amministratore non puo' piu' approvare al posto di BIANCHI");
        f.ok(moved, BIANCHI);
    }

    // ------------------------------------------------------------------
    // Opzioni che avviano un processo collegato
    // ------------------------------------------------------------------

    @Test
    void passaggioDiConsegneChiudeLAvvioEFaPartireLOda() {
        ProcessInstance avvio = f.startAvvio("4500000700", "1000");
        TaskInstance node = f.openTask(avvio.id);
        Requests.Outcome o = f.choose(node.id, ROSSI, "STD");
        assertEquals(ProcessStatus.FORWARDED, f.status(avvio.id), "instradato, non approvato");
        assertTrue(o.message().contains(ODA), o.message());

        ProcessInstance oda = f.childOf(avvio.id, ProcessLink.HANDOFF);
        assertEquals(ODA, oda.processTypeCode);
        assertEquals("4500000700", oda.documentKey);
        assertEquals(ProcessStatus.RUNNING, oda.status);
        assertEquals(Long.valueOf(ROSSI), oda.startedBy);
        assertTrue(f.released.isEmpty(), "nessuna azione di fine sull'avvio");
        assertTrue(f.b.auditTypes(avvio.id).containsAll(List.of("PROCESS_FORWARDED", "PROCESS_SPAWNED")));
    }

    @Test
    void ilFiglioHaIlRichiedenteDelPadreAncheSeSceglieUnAltro() {
        ProcessInstance avvio = f.startAvvio("4500000701", "1000");
        long moved = f.engine.move(f.openTask(avvio.id).id, ROSSI, BIANCHI, "Decidi tu");
        f.choose(moved, BIANCHI, "URG");
        ProcessInstance urg = f.childOf(avvio.id, ProcessLink.HANDOFF);
        assertEquals(ODA_URG, urg.processTypeCode);
        assertEquals(Long.valueOf(ROSSI), urg.startedBy, "richiedente = chi ha avviato il processo di avvio");
        assertEquals(Long.valueOf(NERI), f.openTask(urg.id).assigneeId);
    }

    @Test
    void seIlProcessoNonPuoPartireLaSceltaVieneRifiutata() {
        ProcessInstance avvio = f.startAvvio("4500000702", "1000");
        f.engine.start(new Requests.Start(ODA, "4500000702", null, ROSSI, null));   // gia' aperto per vie traverse
        TaskInstance node = f.openTask(avvio.id);
        Requests.Outcome o = f.choose(node.id, ROSSI, "STD");
        assertEquals(Requests.Outcome.Result.REJECTED, o.result());
        assertTrue(o.message().contains("gia' aperto"), o.message());
        assertEquals(ProcessStatus.RUNNING, f.status(avvio.id), "l'avvio resta dov'era");
        assertEquals(TaskStatus.OPEN, f.openTask(avvio.id).status);
        assertTrue(f.store.links(avvio.id).isEmpty());
    }

    @Test
    void sistemaIrraggiungibileAlMomentoDellaScelta() {
        ProcessInstance avvio = f.startAvvio("4500000703", "1000");
        TaskInstance node = f.openTask(avvio.id);
        f.purchaseOrders.offline(true);
        Requests.Outcome o = f.choose(node.id, ROSSI, "STD");
        f.purchaseOrders.offline(false);
        assertEquals(Requests.Outcome.Result.REJECTED, o.result());
        assertEquals(ProcessStatus.RUNNING, f.status(avvio.id));
        assertEquals(TaskStatus.OPEN, f.openTask(avvio.id).status);
    }

    @Test
    void paralleloIlPadreProsegueEIlFiglioViveAccanto() {
        ProcessInstance avvio = f.startAvvio("4500000704", "1000");
        f.choose(f.openTask(avvio.id).id, ROSSI, "REC");
        assertEquals(20, f.openTask(avvio.id).stepNo, "il padre prosegue al suo step successivo");
        ProcessInstance rec = f.childOf(avvio.id, ProcessLink.PARALLEL);
        assertEquals(RECLAMO, rec.processTypeCode);
        assertEquals(ProcessStatus.RUNNING, rec.status);
        f.ok(f.openTask(avvio.id).id, ROSSI);
        assertEquals(ProcessStatus.COMPLETED, f.status(avvio.id));
        assertEquals(ProcessStatus.RUNNING, f.status(rec.id), "vite indipendenti");
    }

    @Test
    void tornareAlDocumentoChiudeInNegativoSenzaFigli() {
        ProcessInstance avvio = f.startAvvio("4500000705", "1000");
        f.choose(f.openTask(avvio.id).id, ROSSI, "RIV");
        assertEquals(ProcessStatus.REJECTED, f.status(avvio.id));
        assertTrue(f.store.links(avvio.id).isEmpty());
    }

    @Test
    void anteprimaDelleOpzioniSenzaAvviareNulla() {
        ProcessInstance avvio = f.startAvvio("4500000706", "60000");
        TaskInstance node = f.openTask(avvio.id);
        List<Integer> std = f.engine.optionPreview(node.id, "STD").stream().map(Requests.PreviewEntry::stepNo).toList();
        assertEquals(List.of(10, 20, 30, 40), std, "60.000 EUR: tutti i livelli dell'OdA");
        List<Requests.PreviewEntry> urg = f.engine.optionPreview(node.id, "URG");
        assertEquals(1, urg.size());
        assertEquals("Sara Neri", urg.get(0).assignee());
        assertTrue(f.engine.optionPreview(node.id, "RIV").isEmpty());
        assertTrue(f.store.links(avvio.id).isEmpty(), "nessun processo avviato dall'anteprima");
        assertEquals(ProcessStatus.RUNNING, f.status(avvio.id));
    }

    // ------------------------------------------------------------------
    // Coda degli avvii: eventi del documento
    // ------------------------------------------------------------------

    private Requests.EventResult event(String key, Requests.DocumentEventType type, String sapUser) {
        return f.engine.onDocumentEvent(new Requests.DocumentEvent("Q-" + key + "-" + type, "F", key, null, type, sapUser));
    }

    /** Regole: in generale l'OdA va all'approvazione standard; gli ordini ZCL passano dal processo di "Avvio". */
    private void startRules() {
        f.defineStartProcesses();
        f.b.addStartRule(new StartRule(0, "F", Map.of(), 0, ODA));
        f.b.addStartRule(new StartRule(0, "F", Map.of("sap.BSART", "ZCL"), 0, AVVIO));
    }

    private ProcessInstance theOpen(String key) {
        List<ProcessInstance> open = f.store.openProcesses("F", key, null);
        assertEquals(1, open.size(), "processi aperti su " + key);
        return open.get(0);
    }

    @Test
    void creatoAvviaIlProcessoDellaRegolaPiuSpecifica() {
        startRules();
        f.purchaseOrders.value("4500000800", Money.of("1000", "EUR")).context("4500000800", Map.of("sap.BSART", "ZCL"));
        f.purchaseOrders.value("4500000801", Money.of("1000", "EUR")).context("4500000801", Map.of("sap.BSART", "NB"));
        Requests.EventResult a = event("4500000800", Requests.DocumentEventType.CREATED, "MROSSI");
        Requests.EventResult b = event("4500000801", Requests.DocumentEventType.CREATED, "MROSSI");
        assertEquals(Requests.EventResult.EventStatus.DONE, a.status(), a.message());
        assertEquals(AVVIO, theOpen("4500000800").processTypeCode, "ZCL: regola con condizione");
        assertEquals(ODA, theOpen("4500000801").processTypeCode, "altri: regola generica");
        assertEquals(Long.valueOf(ROSSI), theOpen("4500000801").startedBy, "richiedente = utente SAP mappato");
        assertEquals(theOpen("4500000801").id, b.processId());
    }

    @Test
    void utenteSapNonMappatoIlProcessoParteETaskDelRichiedenteAlTeamDiRiserva() {
        startRules();
        f.purchaseOrders.value("4500000802", Money.of("1000", "EUR"));
        Requests.EventResult r = event("4500000802", Requests.DocumentEventType.CREATED, "SCONOSCIUTO");
        assertEquals(Requests.EventResult.EventStatus.WARNING, r.status());
        assertTrue(r.message().contains("SCONOSCIUTO"), r.message());
        ProcessInstance p = theOpen("4500000802");
        assertNull(p.startedBy);
        TaskInstance t10 = f.openTask(p.id);
        assertNull(t10.assigneeId);
        assertEquals("WFADM", t10.teamCode, "il task del richiedente va al team di riserva");
    }

    @Test
    void creatoConProcessoGiaApertoNonNeAvviaUnAltro() {
        startRules();
        ProcessInstance p = f.startOda("4500000803", "1000");
        Requests.EventResult r = event("4500000803", Requests.DocumentEventType.CREATED, "MROSSI");
        assertEquals(Requests.EventResult.EventStatus.DONE, r.status());
        assertEquals(p.id, theOpen("4500000803").id, "idempotente: righe ripetute in coda non duplicano");
    }

    @Test
    void senzaRegolaApplicabileNessunProcesso() {
        f.purchaseOrders.value("4500000804", Money.of("1000", "EUR"));
        Requests.EventResult r = event("4500000804", Requests.DocumentEventType.CREATED, "MROSSI");
        assertEquals(Requests.EventResult.EventStatus.DONE, r.status());
        assertTrue(f.store.openProcesses("F", "4500000804", null).isEmpty());
    }

    @Test
    void rilascioAzzeratoRiavviaIlProcessoAperto() {
        startRules();
        ProcessInstance p = f.startOda("4500000805", "1000");
        f.ok(f.openTask(p.id).id, ROSSI);
        f.purchaseOrders.value("4500000805", Money.of("20000", "EUR"));
        Requests.EventResult r = event("4500000805", Requests.DocumentEventType.RESET, "MROSSI");
        assertEquals(Requests.EventResult.EventStatus.DONE, r.status(), r.message());
        assertEquals(ProcessStatus.CANCELLED, f.status(p.id));
        ProcessInstance n = theOpen("4500000805");
        assertEquals(0, new java.math.BigDecimal("20000").compareTo(n.value.amount()), "il nuovo processo rilegge il documento");
        assertEquals(Long.valueOf(ROSSI), n.startedBy, "stesso richiedente del processo riavviato");
        assertEquals(10, f.openTask(n.id).stepNo, "le approvazioni ripartono");
        assertTrue(f.b.auditTypes(p.id).contains("PROCESS_RESTARTED"));
    }

    @Test
    void rilascioAzzeratoSenzaProcessoApertoLoAvvia() {
        startRules();
        f.purchaseOrders.value("4500000806", Money.of("1000", "EUR"));
        event("4500000806", Requests.DocumentEventType.RESET, "MROSSI");
        assertEquals(ODA, theOpen("4500000806").processTypeCode);
    }

    @Test
    void modificaSenzaVariazioniIgnorataConVariazioniRiavvia() {
        startRules();
        DocItem p10 = new DocItem("00010", new java.math.BigDecimal("10"), new java.math.BigDecimal("700"));
        DocItem p20 = new DocItem("00020", new java.math.BigDecimal("5"), new java.math.BigDecimal("300"));
        f.purchaseOrders.items("4500000807", List.of(p10, p20));
        ProcessInstance p = f.startOda("4500000807", "1000");
        assertEquals(List.of(p10, p20), f.store.process(p.id).orElseThrow().docItems, "posizioni fotografate all'avvio");

        Requests.EventResult same = event("4500000807", Requests.DocumentEventType.CHANGED, "MROSSI");
        assertEquals(Requests.EventResult.EventStatus.DONE, same.status());
        assertEquals(p.id, theOpen("4500000807").id, "testo cambiato, posizioni no: si prosegue");
        assertTrue(f.b.auditTypes(p.id).contains("DOCUMENT_CHANGE_IGNORED"));

        // +2 sulla 10, -2 sulla 20: totale invariato, ma e' una modifica
        f.purchaseOrders.items("4500000807", List.of(
                new DocItem("00010", new java.math.BigDecimal("12"), new java.math.BigDecimal("700")),
                new DocItem("00020", new java.math.BigDecimal("3"), new java.math.BigDecimal("300"))));
        Requests.EventResult qty = event("4500000807", Requests.DocumentEventType.CHANGED, "MROSSI");
        assertTrue(qty.message().contains("posizione 00010"), qty.message());
        assertEquals(ProcessStatus.CANCELLED, f.status(p.id), "tolleranza 0: qualsiasi variazione riavvia");
        assertTrue(p.id != theOpen("4500000807").id, "nuovo processo");
    }

    @Test
    void cancellazioneAnnullaIProcessiAperti() {
        ProcessInstance p = f.startOda("4500000808", "1000");
        event("4500000808", Requests.DocumentEventType.DELETED, "MROSSI");
        assertEquals(ProcessStatus.CANCELLED, f.status(p.id));
        assertEquals(WorkflowEngine.REASON_DOCUMENT_DELETED, f.store.process(p.id).orElseThrow().statusReason);
    }

    @Test
    void sistemaIrraggiungibileErroreDaRitentareENessunProcesso() {
        startRules();
        f.purchaseOrders.value("4500000809", Money.of("1000", "EUR"));
        f.purchaseOrders.offline(true);
        Requests.EventResult r = event("4500000809", Requests.DocumentEventType.CREATED, "MROSSI");
        f.purchaseOrders.offline(false);
        assertEquals(Requests.EventResult.EventStatus.ERROR, r.status());
        assertTrue(r.message().contains("BACKEND_UNAVAILABLE"), r.message());
        assertTrue(f.store.openProcesses("F", "4500000809", null).isEmpty());
        assertEquals(Requests.EventResult.EventStatus.DONE,
                event("4500000809", Requests.DocumentEventType.CREATED, "MROSSI").status(), "ritentato: va");
    }

    @Test
    void ilProcessoreLeggeElaboraEConferma() {
        startRules();
        f.purchaseOrders.value("4500000810", Money.of("1000", "EUR"));
        List<Requests.EventResult> confirmed = new java.util.ArrayList<>();
        it.eone.ewf.engine.spi.StartSource src = new it.eone.ewf.engine.spi.StartSource() {
            boolean given;
            @Override public List<Requests.DocumentEvent> fetch(int max) {
                if (given) return List.of();
                given = true;
                return List.of(new Requests.DocumentEvent("Q1", "F", "4500000810", null,
                        Requests.DocumentEventType.CREATED, "MROSSI"));
            }
            @Override public void confirm(List<Requests.EventResult> results) { confirmed.addAll(results); }
        };
        StartQueueProcessor proc = new StartQueueProcessor(f.engine, src, 50);
        assertEquals(1, proc.runOnce().size());
        assertEquals(List.of("Q1"), confirmed.stream().map(Requests.EventResult::queueId).toList());
        assertTrue(proc.runOnce().isEmpty(), "coda vuota");
        assertEquals(ODA, theOpen("4500000810").processTypeCode);
    }

    @Test
    void eventoFallitoTrattieneIdSuccessiviDelloStessoDocumento() {
        startRules();
        f.purchaseOrders.missing("4500000811");                     // CREATED fallira'
        f.purchaseOrders.value("4500000812", Money.of("1000", "EUR"));
        List<Requests.EventResult> confirmed = new java.util.ArrayList<>();
        it.eone.ewf.engine.spi.StartSource src = new it.eone.ewf.engine.spi.StartSource() {
            @Override public List<Requests.DocumentEvent> fetch(int max) {
                return List.of(
                        new Requests.DocumentEvent("Q1", "F", "4500000811", null, Requests.DocumentEventType.CREATED, "MROSSI"),
                        new Requests.DocumentEvent("Q2", "F", "4500000812", null, Requests.DocumentEventType.CREATED, "MROSSI"),
                        new Requests.DocumentEvent("Q3", "F", "4500000811", null, Requests.DocumentEventType.DELETED, "MROSSI"));
            }
            @Override public void confirm(List<Requests.EventResult> results) { confirmed.addAll(results); }
        };
        new StartQueueProcessor(f.engine, src, 50).runOnce();
        assertEquals(List.of("Q1", "Q2"), confirmed.stream().map(Requests.EventResult::queueId).toList(),
                "Q3 resta in coda dietro a Q1");
        assertEquals(Requests.EventResult.EventStatus.ERROR, confirmed.get(0).status());
        assertEquals(Requests.EventResult.EventStatus.DONE, confirmed.get(1).status(), "gli altri documenti vanno avanti");
    }
}
