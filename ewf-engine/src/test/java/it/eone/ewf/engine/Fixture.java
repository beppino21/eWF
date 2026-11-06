package it.eone.ewf.engine;

import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.assign.RuleBasedAssignmentResolver;
import it.eone.ewf.engine.memory.MapDocumentAdapters;
import it.eone.ewf.engine.memory.MapHandlerRegistry;
import it.eone.ewf.engine.memory.RateCurrencyConverter;
import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.HandlerResult;
import it.eone.ewf.engine.spi.RuntimeStore;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ambiente di prova che riproduce EONE_ODA del sistema di riferimento:
 * 10 avvio (richiedente) -> 20 Responsabile Acquisti -> 30 2° rilascio (>= 10.000)
 * -> 40 3° rilascio (>= 50.000) -> fine con rilascio OdA. Rifiuto: torna allo step 10.
 */
final class Fixture {

    static final long ROSSI = 1, BIANCHI = 2, VERDI = 3, NERI = 4, GIALLI = 5, ACQ1 = 6, ACQ2 = 7, ADMIN = 9;
    static final String ODA = "EONE_ODA";
    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    final Backend b;
    final RuntimeStore store;
    final MapHandlerRegistry handlers = new MapHandlerRegistry();
    final MapDocumentAdapters.Fixed purchaseOrders = new MapDocumentAdapters.Fixed();
    final MapDocumentAdapters adapters = new MapDocumentAdapters().register("F", purchaseOrders);
    final RateCurrencyConverter fx = new RateCurrencyConverter().rate("USD", "EUR", "0.90");
    final WorkflowEngine engine;

    /** Comportamento degli handler finti, modificabile dai test. */
    HandlerResult lockCheck = HandlerResult.ok();
    final List<String> released = new ArrayList<>();
    int releaseFailures = 0;

    Fixture(Backend backend) {
        b = backend;
        store = b.store();
        b.addUser(ROSSI, "ROSSI", "Mario Rossi", "MROSSI", 0);
        b.addUser(BIANCHI, "BIANCHI", "Anna Bianchi", "ABIANCHI", 100);
        b.addUser(VERDI, "VERDI", "Luca Verdi", "LVERDI", 200);
        b.addUser(NERI, "NERI", "Sara Neri", "SNERI", 200);
        b.addUser(GIALLI, "GIALLI", "Paolo Gialli", "PGIALLI", 200);
        b.addUser(ACQ1, "ACQ1", "Ufficio Acquisti 1", null, 100);
        b.addUser(ACQ2, "ACQ2", "Ufficio Acquisti 2", null, 50);
        b.addUser(ADMIN, "ADMIN", "Amministratore WF", null, 999);
        b.teamMember("ACQ", ACQ1);
        b.teamMember("ACQ", ACQ2);
        b.teamMember("WFADM", ADMIN);

        b.addRule(rule(1, "RESPONSABILE_ACQ", null, BIANCHI, null));
        b.addRule(rule(2, "RESPONSABILE_ACQ_L1", null, VERDI, null));
        b.addRule(rule(3, "RESPONSABILE_ACQ_L2", null, NERI, null));

        handlers.register("/EWF/WF_CHECK_LOCK_EKKO", ctx -> lockCheck);
        handlers.register("/EWF/WF_RIL_ODA", ctx -> {
            if (releaseFailures > 0) {
                releaseFailures--;
                return HandlerResult.ko("BAPI_PO_RELEASE: codice di rilascio non valido");
            }
            released.add(ctx.process().documentKey());
            return HandlerResult.ok();
        });
        handlers.register("/EWF/SEND_MAIL_NEW_TASK", ctx -> HandlerResult.ok());

        b.defineType(new ProcessType(ODA, "Autorizzazione OdA", "F", false, false, false));
        b.activate(odaVersion(1, PreviewMode.NAME));

        engine = new WorkflowEngine(b.defs(), store, b.users(),
                new RuleBasedAssignmentResolver(b.rules(), fx), handlers, adapters, fx, b.audit(), b.tx(),
                Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ROME),
                new EngineConfig("WFADM", ROME));
    }

    static AssignmentRule rule(long id, String role, String plant, Long user, String team) {
        return new AssignmentRule(id, null, role, null, plant, null, null, null, null, user, team, null, null);
    }

    static TaskDef task(long id, String text, String role, int weight, AssigneeMode mode, HookDef... hooks) {
        return new TaskDef(id, 0, text, role, weight, mode, null, false, null, PreviewMode.NAME,
                Interaction.WEB, null, List.of(hooks));
    }

    ProcessVersion odaVersion(long id, PreviewMode l2Preview) {
        HookDef mail = HookDef.of(HookEvent.ON_ACTIVATE, "/EWF/SEND_MAIL_NEW_TASK");
        HookDef lock = HookDef.of(HookEvent.BEFORE_ACTION, "/EWF/WF_CHECK_LOCK_EKKO");
        TaskDef l2 = new TaskDef(id * 100 + 4, 0, "3° Rilascio Responsabile Acquisti", "RESPONSABILE_ACQ_L2", 200,
                AssigneeMode.RULES, null, false, null, l2Preview, Interaction.WEB, null, List.of(mail, lock));
        return new ProcessVersion(id, ODA, (int) id, "/EWF/WF_RIL_ODA", List.of(
                new StepDef(10, true, JoinPolicy.ALL, 20, null, null,
                        List.of(task(id * 100 + 1, "Avvio processo Autorizz. ODA", "UFFICIO_ACQUISTI", 0, AssigneeMode.STARTER, mail))),
                new StepDef(20, false, JoinPolicy.ALL, 30, 10, null,
                        List.of(task(id * 100 + 2, "Rilascio Responsabile Acquisti", "RESPONSABILE_ACQ", 100, AssigneeMode.RULES, mail, lock))),
                new StepDef(30, false, JoinPolicy.ALL, 40, 10, Money.of("10000", "EUR"),
                        List.of(task(id * 100 + 3, "2° Rilascio Responsabile Acquisti", "RESPONSABILE_ACQ_L1", 200, AssigneeMode.RULES, mail, lock))),
                new StepDef(40, false, JoinPolicy.ALL, null, 10, Money.of("50000", "EUR"), List.of(l2))));
    }

    ProcessInstance startOda(String ebeln, String amountEur) {
        purchaseOrders.value(ebeln, Money.of(amountEur, "EUR"));
        return engine.start(new Requests.Start(ODA, ebeln, null, ROSSI, null));
    }

    TaskInstance openTask(long processId) {
        List<TaskInstance> open = store.tasksOf(processId).stream().filter(TaskInstance::isOpen).toList();
        if (open.size() != 1) throw new AssertionError("Attesi 1 task aperto, trovati " + open.size());
        return open.get(0);
    }

    List<TaskInstance> openTasks(long processId) {
        return store.tasksOf(processId).stream().filter(TaskInstance::isOpen).toList();
    }

    Requests.Outcome ok(long taskId, long actor) {
        return engine.act(new Requests.Act(taskId, actor, Requests.Action.OK, null, null));
    }

    Requests.Outcome ko(long taskId, long actor, String note) {
        return engine.act(new Requests.Act(taskId, actor, Requests.Action.KO, note, null));
    }

    ProcessStatus status(long processId) {
        return store.process(processId).orElseThrow().status;
    }

    static Map<String, String> none() { return Map.of(); }

    static BigDecimal bd(String s) { return new BigDecimal(s); }
}
