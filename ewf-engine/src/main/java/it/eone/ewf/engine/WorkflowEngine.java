package it.eone.ewf.engine;

import it.eone.ewf.engine.EngineException.Code;
import it.eone.ewf.engine.Requests.*;
import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.*;
import it.eone.ewf.engine.spi.HandlerContext.Actor;
import it.eone.ewf.engine.spi.HandlerContext.ProcessRef;
import it.eone.ewf.engine.spi.HandlerContext.TaskRef;
import it.eone.ewf.engine.spi.HandlerResult.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Motore eWF.
 *
 * Regole fondamentali:
 *  - lo stato lo cambia solo il motore; gli handler restituiscono esiti e istruzioni;
 *  - gli handler sono chiamati FUORI dalle transazioni: prima il task passa a EXECUTING
 *    (transazione 1), poi si chiamano gli handler, poi si applica l'esito (transazione 2);
 *  - esito UNKNOWN => processo in ERROR, il task resta EXECUTING finche' un amministratore
 *    non conferma l'esito con resolveUnknown();
 *  - un processo resta sempre sulla versione di definizione con cui e' partito.
 */
public final class WorkflowEngine {

    public static final String REASON_END_FAILED = "END_FAILED";
    public static final String REASON_UNKNOWN = "UNKNOWN_EXECUTION";
    public static final String REASON_RESTARTED = "RESTARTED";

    private final DefinitionStore defs;
    private final RuntimeStore store;
    private final UserDirectory users;
    private final AssignmentResolver resolver;
    private final HandlerRegistry handlers;
    private final DocumentAdapterRegistry adapters;
    private final CurrencyConverter currency;
    private final AuditSink audit;
    private final Transactions tx;
    private final Clock clock;
    private final EngineConfig config;

    public WorkflowEngine(DefinitionStore defs, RuntimeStore store, UserDirectory users,
                          AssignmentResolver resolver, HandlerRegistry handlers,
                          DocumentAdapterRegistry adapters, CurrencyConverter currency,
                          AuditSink audit, Transactions tx, Clock clock, EngineConfig config) {
        this.defs = defs; this.store = store; this.users = users; this.resolver = resolver;
        this.handlers = handlers; this.adapters = adapters; this.currency = currency;
        this.audit = audit; this.tx = tx; this.clock = clock; this.config = config;
    }

    // =====================================================================
    // Avvio
    // =====================================================================

    public ProcessInstance start(Start req) {
        ProcessType pt = defs.processType(req.processType())
                .orElseThrow(() -> EngineException.of(Code.NOT_FOUND, "Tipo processo %s inesistente", req.processType()));
        if (pt.suspended())
            throw EngineException.of(Code.PROCESS_TYPE_SUSPENDED, "Tipo processo %s sospeso", pt.code());
        ProcessVersion v = defs.activeVersion(pt.code())
                .orElseThrow(() -> EngineException.of(Code.NO_ACTIVE_VERSION, "Nessuna versione attiva per %s", pt.code()));
        requireActiveUser(req.starterId());

        Money value = req.value() != null ? req.value() : documentValue(pt.docType(), req.documentKey()).orElse(null);

        ProcessInstance p = tx.inTransaction(() -> {
            if (store.hasOpenProcess(pt.code(), pt.docType(), req.documentKey(), req.backend()))
                throw EngineException.of(Code.DUPLICATE_PROCESS,
                        "Processo %s gia' avviato per il documento %s", pt.code(), req.documentKey());
            ProcessInstance n = new ProcessInstance();
            n.versionId = v.id();
            n.processTypeCode = pt.code();
            n.docType = pt.docType();
            n.documentKey = req.documentKey();
            n.backendCode = req.backend();
            n.value = value;
            n.startedBy = req.starterId();
            n.startedAt = now();
            try {
                n = store.insertProcess(n);
            } catch (RuntimeStore.DuplicateOpenProcessException e) {
                throw EngineException.of(Code.DUPLICATE_PROCESS,
                        "Processo %s gia' avviato per il documento %s", pt.code(), req.documentKey());
            }
            audit("PROCESS_STARTED", req.starterId(), "USER", n.id, null,
                    Map.of("version", String.valueOf(v.versionNo()), "document", req.documentKey()));
            return n;
        });

        StepDef first = v.firstStep();
        Integer startStep = pt.noFirstStep() ? first.nextOk() : first.stepNo();
        List<TaskInstance> created = activateFrom(p.id, startStep, new Activation(req.starterId(), req.starterId()));

        if (pt.autoOkFirst() && !pt.noFirstStep()) {
            for (TaskInstance t : created) {
                if (t.stepNo == first.stepNo())
                    act(new Act(t.id, req.starterId(), Action.OK, "Approvazione automatica all'avvio", null), true);
            }
        }
        return reloadProcess(p.id);
    }

    // =====================================================================
    // Approva / rifiuta
    // =====================================================================

    public Outcome act(Act req) {
        return act(req, false);
    }

    private Outcome act(Act req, boolean system) {
        record Prepared(ProcessInstance p, ProcessVersion v, TaskDef td, TaskInstance t, boolean asSubst) {}

        Prepared prep = guarded(() -> tx.inTransaction(() -> {
            TaskInstance t = requireTask(req.taskId());
            if (t.status != TaskStatus.OPEN)
                throw EngineException.of(Code.INVALID_STATE, "Task %d non aperto (%s)", t.id, t.status);
            if (req.expectedRowVersion() != null && req.expectedRowVersion() != t.rowVersion)
                throw EngineException.of(Code.CONCURRENT_UPDATE, "Task %d modificato da un altro utente", t.id);
            ProcessInstance p = requireProcess(t.processId);
            if (p.status != ProcessStatus.RUNNING)
                throw EngineException.of(Code.INVALID_STATE, "Processo %d non in corso (%s)", p.id, p.status);
            ProcessVersion v = defs.version(p.versionId);
            TaskDef td = v.task(t.taskDefId);
            if (td.interaction() == Interaction.SAPGUI_ONLY && !system)
                throw EngineException.of(Code.INVALID_STATE, "Task %d eseguibile solo da SAP GUI", t.id);
            boolean asSubst = system ? false : authorize(req.actorId(), t, p, td, true);
            t.status = TaskStatus.EXECUTING;
            t.action = req.action().name();
            t.actedById = system ? null : req.actorId();
            t.actedAsSubstitute = asSubst;
            t.note = req.note();
            store.updateTask(t);
            return new Prepared(p, v, td, t, asSubst);
        }));

        Actor actor = system ? Actor.systemActor() : actorOf(req.actorId(), prep.asSubst());

        // BEFORE_ACTION: controlli (ex FM_EXANTE). KO o UNKNOWN bloccano l'azione.
        for (HookDef h : prep.td().hooks(HookEvent.BEFORE_ACTION)) {
            Executed ex = invoke(prep.p(), prep.t(), Contract.TASK_ACTION, HookEvent.BEFORE_ACTION, h.handlerCode(),
                    h.config(), req.action().name(), actor, prep.td());
            if (ex.result().status() != HandlerResult.Status.OK) {
                reopen(prep.t().id, "ACTION_BLOCKED", ex.result().message());
                return new Outcome(Outcome.Result.REJECTED, ex.result().message(), ProcessStatus.RUNNING, List.of());
            }
        }

        // ON_OK / ON_KO: esecuzione (ex FM_OK / FM_KO)
        HookEvent event = req.action() == Action.OK ? HookEvent.ON_OK : HookEvent.ON_KO;
        List<Directive> directives = new ArrayList<>();
        Long lastExecId = null;
        for (HookDef h : prep.td().hooks(event)) {
            Executed ex = invoke(prep.p(), prep.t(), Contract.TASK_ACTION, event, h.handlerCode(),
                    h.config(), req.action().name(), actor, prep.td());
            switch (ex.result().status()) {
                case KO -> {
                    reopen(prep.t().id, "ACTION_FAILED", ex.result().message());
                    return new Outcome(Outcome.Result.REJECTED, ex.result().message(), ProcessStatus.RUNNING, List.of());
                }
                case UNKNOWN -> {
                    markUnknown(prep.p().id, ex.executionId(), ex.result().message());
                    return new Outcome(Outcome.Result.UNKNOWN, ex.result().message(), ProcessStatus.ERROR, List.of());
                }
                case OK -> {
                    directives.addAll(ex.result().directives());
                    lastExecId = ex.executionId();
                }
            }
        }
        return complete(prep.t().id, directives, lastExecId);
    }

    /** Chiude il task in EXECUTING applicando l'azione registrata in task.action, poi avanza. */
    private Outcome complete(long taskId, List<Directive> directives, Long sourceExecId) {
        record Next(Integer step, boolean go, Activation activation, ProcessStatus status, String message) {
            Next(Integer step, boolean go, Activation activation, ProcessStatus status) {
                this(step, go, activation, status, null);
            }
        }

        Next next = tx.inTransaction(() -> {
            // prima il lock sul processo: due paralleli chiusi insieme si mettono in fila,
            // cosi' il secondo vede il primo gia' chiuso (altrimenti entrambi "aspetterebbero")
            store.lockProcess(store.task(taskId).orElseThrow(() ->
                    EngineException.of(Code.NOT_FOUND, "Task %d inesistente", taskId)).processId);
            TaskInstance t = requireTask(taskId);
            ProcessInstance p = requireProcess(t.processId);
            if (t.status != TaskStatus.EXECUTING) {
                // un parallelo (join ANY) o un annullamento l'ha gia' chiuso mentre eseguivamo gli handler
                audit("ACTION_SUPERSEDED", t.actedById, "SYSTEM", p.id, t.id, Map.of("status", t.status.name()));
                return new Next(null, false, null, p.status, "Task gia' chiuso (" + t.status + ")");
            }
            ProcessVersion v = defs.version(p.versionId);
            Action action = Action.valueOf(t.action);

            t.status = action == Action.OK ? TaskStatus.OK : TaskStatus.KO;
            t.closedAt = now();
            store.updateTask(t);
            applyDirectives(p, directives, sourceExecId);
            audit("TASK_" + action.name(), t.actedById, t.actedById == null ? "SYSTEM" : "USER", p.id, t.id,
                    t.actedAsSubstitute ? Map.of("asSubstitute", "true") : Map.of());

            StepDef step = v.step(t.stepNo).orElseThrow();
            List<TaskInstance> siblings = store.tasksOf(p.id).stream()
                    .filter(o -> o.id != t.id && o.stepNo == t.stepNo && o.isOpen()).toList();
            Long actor = t.actedById;
            Activation act = new Activation(actor, t.assigneeId);

            if (action == Action.OK) {
                if (!siblings.isEmpty() && step.join() == JoinPolicy.ALL)
                    return new Next(null, false, act, p.status);          // aspetta gli altri paralleli
                cancel(siblings, p.id, "JOIN_ANY");
                return new Next(step.nextOk(), true, act, p.status);
            }
            // KO: un solo rifiuto chiude lo step
            cancel(siblings, p.id, "STEP_REJECTED");
            store.clearStepStates(p.id, "HANDLER");                      // ex reset Z_WF_SKIP sul rifiuto
            if (step.nextKo() == null) {
                p.status = ProcessStatus.REJECTED;
                p.endedAt = now();
                p.endedBy = actor;
                store.updateProcess(p);
                audit("PROCESS_REJECTED", actor, "USER", p.id, t.id, Map.of());
                return new Next(null, false, act, p.status);
            }
            return new Next(step.nextKo(), true, act, p.status);
        });

        if (!next.go()) {
            return new Outcome(Outcome.Result.DONE, next.message(), next.status(), List.of());
        }
        long processId = requireTask(taskId).processId;
        List<TaskInstance> created = activateFrom(processId, next.step(), next.activation());
        return new Outcome(Outcome.Result.DONE, null, reloadProcess(processId).status,
                created.stream().map(t -> t.id).toList());
    }

    // =====================================================================
    // Sposta (delega puntuale, ex MOVE)
    // =====================================================================

    public long move(long taskId, long actorId, long toUserId, String note) {
        long newTaskId = guarded(() -> tx.inTransaction(() -> {
            TaskInstance t = requireTask(taskId);
            if (t.status != TaskStatus.OPEN)
                throw EngineException.of(Code.INVALID_STATE, "Task %d non aperto (%s)", t.id, t.status);
            ProcessInstance p = requireProcess(t.processId);
            if (p.status != ProcessStatus.RUNNING)
                throw EngineException.of(Code.INVALID_STATE, "Processo %d non in corso", p.id);
            TaskDef td = defs.version(p.versionId).task(t.taskDefId);
            boolean asSubst = authorize(actorId, t, p, td, false);
            User to = requireActiveUser(toUserId);

            t.status = TaskStatus.MOVED;
            t.movedToId = to.id();
            t.actedById = actorId;
            t.actedAsSubstitute = asSubst;
            t.action = "MOVE";
            t.note = note;
            t.closedAt = now();
            store.updateTask(t);

            TaskInstance n = new TaskInstance();
            n.processId = t.processId; n.taskDefId = t.taskDefId; n.stepNo = t.stepNo; n.subopNo = t.subopNo;
            n.assigneeId = to.id(); n.teamCode = t.teamCode; n.roleCode = t.roleCode;
            n.fromUserId = actorId; n.createdAt = now();
            n = store.insertTask(n);
            audit("TASK_MOVED", actorId, "USER", p.id, t.id, Map.of("to", to.login(), "newTask", String.valueOf(n.id)));
            return n.id;
        }));
        TaskInstance n = requireTask(newTaskId);
        ProcessInstance p = requireProcess(n.processId);
        runActivateHooks(p, n, defs.version(p.versionId).task(n.taskDefId));
        return newTaskId;
    }

    // =====================================================================
    // Task di team: presa in carico
    // =====================================================================

    public void claim(long taskId, long actorId) {
        guarded(() -> tx.inTransaction(() -> {
            TaskInstance t = requireTask(taskId);
            if (t.status != TaskStatus.OPEN)
                throw EngineException.of(Code.INVALID_STATE, "Task %d non aperto", t.id);
            ProcessInstance p = requireProcess(t.processId);
            TaskDef td = defs.version(p.versionId).task(t.taskDefId);
            authorize(actorId, t, p, td, false);
            if (Objects.equals(t.claimedById, actorId)) return null;
            t.claimedById = actorId;
            t.claimedAt = now();
            store.updateTask(t);
            audit("TASK_CLAIMED", actorId, "USER", p.id, t.id, Map.of());
            return null;
        }));
    }

    public void release(long taskId, long actorId) {
        tx.run(() -> {
            TaskInstance t = requireTask(taskId);
            if (!Objects.equals(t.claimedById, actorId))
                throw EngineException.of(Code.NOT_AUTHORIZED, "Task %d non in carico all'utente", t.id);
            t.claimedById = null;
            t.claimedAt = null;
            store.updateTask(t);
            audit("TASK_RELEASED", actorId, "USER", t.processId, t.id, Map.of());
        });
    }

    // =====================================================================
    // Chiusura, errori, riavvio, annullamento
    // =====================================================================

    /** Riprova l'azione di fine processo dopo un errore. */
    public ProcessInstance retryCompletion(long processId, long adminId) {
        tx.run(() -> {
            ProcessInstance p = requireProcess(processId);
            if (p.status != ProcessStatus.ERROR || p.statusReason == null || !p.statusReason.startsWith(REASON_END_FAILED))
                throw EngineException.of(Code.INVALID_STATE, "Processo %d non in errore di chiusura", p.id);
            p.status = ProcessStatus.COMPLETING;
            p.statusReason = null;
            store.updateProcess(p);
            audit("COMPLETION_RETRY", adminId, "USER", p.id, null, Map.of());
        });
        runCompletion(processId);
        return reloadProcess(processId);
    }

    /**
     * Esito UNKNOWN di un handler: l'amministratore verifica in SAP e conferma.
     * succeeded = true: l'azione e' avvenuta, il task si chiude e il processo avanza.
     * succeeded = false: non e' avvenuta, il task torna aperto.
     */
    public Outcome resolveUnknown(long executionId, boolean succeeded, long adminId, String note) {
        HandlerExecution e = store.execution(executionId)
                .orElseThrow(() -> EngineException.of(Code.NOT_FOUND, "Esecuzione %d inesistente", executionId));
        if (e.status != HandlerExecution.Status.UNKNOWN)
            throw EngineException.of(Code.INVALID_STATE, "Esecuzione %d non in stato UNKNOWN", executionId);
        tx.run(() -> {
            e.status = succeeded ? HandlerExecution.Status.OK : HandlerExecution.Status.KO;
            e.message = (e.message == null ? "" : e.message + " | ") + "Risolto manualmente: " + note;
            e.endedAt = now();
            store.updateExecution(e);
            ProcessInstance p = requireProcess(e.processId);
            p.status = ProcessStatus.RUNNING;
            p.statusReason = null;
            store.updateProcess(p);
            audit("UNKNOWN_RESOLVED", adminId, "USER", p.id, e.taskId,
                    Map.of("execution", String.valueOf(executionId), "succeeded", String.valueOf(succeeded)));
        });
        if (e.taskId == null) {
            if (succeeded) finish(e.processId);
            return new Outcome(Outcome.Result.DONE, null, reloadProcess(e.processId).status, List.of());
        }
        if (!succeeded) {
            reopen(e.taskId, "ACTION_FAILED", "Esito confermato negativo");
            return new Outcome(Outcome.Result.REJECTED, note, ProcessStatus.RUNNING, List.of());
        }
        return complete(e.taskId, List.of(), executionId);
    }

    /** Annulla il processo e lo riavvia sulla versione attiva, collegando i due. */
    public ProcessInstance restart(long processId, long actorId, String note) {
        ProcessInstance old = cancelInternal(processId, actorId, REASON_RESTARTED, note);
        ProcessInstance n = start(new Start(old.processTypeCode, old.documentKey, old.backendCode, old.startedBy, null));
        tx.run(() -> {
            store.insertLink(new ProcessLink(old.id, n.id, ProcessLink.RESTART));
            audit("PROCESS_RESTARTED", actorId, "USER", old.id, null, Map.of("newProcess", String.valueOf(n.id)));
        });
        return n;
    }

    public ProcessInstance cancel(long processId, long actorId, String note) {
        return cancelInternal(processId, actorId, "CANCELLED", note);
    }

    private ProcessInstance cancelInternal(long processId, long actorId, String reason, String note) {
        return tx.inTransaction(() -> {
            ProcessInstance p = requireProcess(processId);
            if (!p.status.isOpen())
                throw EngineException.of(Code.INVALID_STATE, "Processo %d gia' chiuso (%s)", p.id, p.status);
            cancel(store.tasksOf(p.id).stream().filter(TaskInstance::isOpen).toList(), p.id, reason);
            p.status = ProcessStatus.CANCELLED;
            p.statusReason = reason;
            p.endedAt = now();
            p.endedBy = actorId;
            store.updateProcess(p);
            audit("PROCESS_CANCELLED", actorId, "USER", p.id, null,
                    note == null ? Map.of("reason", reason) : Map.of("reason", reason, "note", note));
            return p;
        });
    }

    // =====================================================================
    // Consultazione: inbox e anteprima
    // =====================================================================

    public List<InboxEntry> inbox(long userId) {
        LocalDate today = today();
        Set<Long> substituted = users.substitutedBy(userId, today);
        Set<String> teams = users.teamsOf(userId, today);
        Set<Long> assignees = new HashSet<>(substituted);
        assignees.add(userId);
        List<InboxEntry> out = new ArrayList<>();
        for (TaskInstance t : store.openTasksFor(assignees, teams)) {
            InboxEntry.Reason r = Objects.equals(t.assigneeId, userId) ? InboxEntry.Reason.OWN
                    : (t.assigneeId != null && substituted.contains(t.assigneeId)) ? InboxEntry.Reason.SUBSTITUTE
                    : InboxEntry.Reason.TEAM;
            out.add(new InboxEntry(t.id, t.processId, r));
        }
        return out;
    }

    /** Catena prevista lungo il percorso di approvazione, nel rispetto di preview_mode. */
    public List<PreviewEntry> preview(long processId) {
        ProcessInstance p = requireProcess(processId);
        if (p.status != ProcessStatus.RUNNING) return List.of();
        ProcessVersion v = defs.version(p.versionId);
        OptionalInt current = store.tasksOf(p.id).stream().filter(TaskInstance::isOpen).mapToInt(t -> t.stepNo).min();
        if (current.isEmpty()) return List.of();
        Map<Integer, StepState> states = store.stepStates(p.id);
        List<PreviewEntry> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Integer n = v.step(current.getAsInt()).orElseThrow().nextOk();
        while (n != null && seen.add(n)) {
            StepDef s = v.step(n).orElseThrow();
            StepState st = states.get(n);
            if ((st != null && st.skipped()) || belowMinimum(p.value, s.minAmount())) {
                n = s.nextOk();
                continue;
            }
            for (TaskDef td : s.tasks()) {
                switch (td.previewMode()) {
                    case HIDDEN -> { }
                    case ROLE -> out.add(new PreviewEntry(n, td.text(), td.roleCode(), null, null));
                    case NAME -> out.add(previewName(p, n, td));
                }
            }
            n = s.nextOk();
        }
        return out;
    }

    private PreviewEntry previewName(ProcessInstance p, int stepNo, TaskDef td) {
        String who = null;
        String note = null;
        switch (td.assigneeMode()) {
            case STARTER -> who = users.byId(p.startedBy).map(User::displayName).orElse(null);
            case RULES -> {
                Optional<AssignmentResolver.Assignment> a = resolver.resolve(new AssignmentResolver.Query(
                        p.processTypeCode, td.roleCode(), documentContext(p), p.value, today()));
                who = a.map(x -> x.userId() != null
                                ? users.byId(x.userId()).map(User::displayName).orElse(null)
                                : "Team " + x.teamCode())
                        .orElse(null);
                if (who == null) note = "nessuna regola applicabile oggi";
            }
            default -> note = "determinato all'attivazione";
        }
        return new PreviewEntry(stepNo, td.text(), td.roleCode(), who, note);
    }

    // =====================================================================
    // Attivazione step e creazione task
    // =====================================================================

    /** actorId: chi ha agito sul task precedente (o l'avviante); previousAssigneeId: titolare precedente. */
    private record Activation(Long actorId, Long previousAssigneeId) {}

    private record Assignee(Long userId, String teamCode, boolean fallback) {}

    private List<TaskInstance> activateFrom(long processId, Integer stepNo, Activation act) {
        ProcessInstance p = refreshValue(processId);
        ProcessVersion v = defs.version(p.versionId);
        Map<Integer, StepState> states = store.stepStates(p.id);
        Set<Integer> seen = new HashSet<>();
        Integer n = stepNo;
        while (true) {
            if (n == null) {
                beginCompletion(p.id);
                return List.of();
            }
            if (!seen.add(n))
                throw EngineException.of(Code.INVALID_DEFINITION, "Ciclo di step saltati nel processo %d", p.id);
            final int stepNo2 = n;
            StepDef s = v.step(n).orElseThrow(() ->
                    EngineException.of(Code.INVALID_DEFINITION, "Step %d inesistente nella versione %d", stepNo2, v.id()));
            StepState st = states.get(n);
            if (st != null && st.skipped()) {
                auditTx("STEP_SKIPPED", null, "SYSTEM", p.id, null, Map.of("step", String.valueOf(n), "reason", st.reason()));
                n = s.nextOk();
                continue;
            }
            if (belowMinimum(p.value, s.minAmount())) {
                auditTx("STEP_SKIPPED", null, "SYSTEM", p.id, null, Map.of("step", String.valueOf(n), "reason", "AMOUNT_BELOW_MIN"));
                n = s.nextOk();
                continue;
            }
            return createTasks(p, s, act);
        }
    }

    private List<TaskInstance> createTasks(ProcessInstance p, StepDef s, Activation act) {
        // 1) assegnatari: puo' chiamare handler ASSIGNEE, quindi fuori transazione
        Map<TaskDef, List<Assignee>> plan = new LinkedHashMap<>();
        for (TaskDef td : s.tasks()) plan.put(td, resolveAssignees(p, td, act));

        // 2) inserimento task
        List<TaskInstance> created = tx.inTransaction(() -> {
            List<TaskInstance> out = new ArrayList<>();
            for (Map.Entry<TaskDef, List<Assignee>> e : plan.entrySet()) {
                TaskDef td = e.getKey();
                List<Assignee> as = e.getValue();
                for (int i = 0; i < as.size(); i++) {
                    Assignee a = as.get(i);
                    TaskInstance t = new TaskInstance();
                    t.processId = p.id;
                    t.taskDefId = td.id();
                    t.stepNo = s.stepNo();
                    t.subopNo = as.size() == 1 ? td.subopNo() : td.subopNo() * 100 + i + 1;  // paralleli generati
                    t.assigneeId = a.userId();
                    t.teamCode = a.teamCode();
                    t.roleCode = td.roleCode();
                    t.fromUserId = act.actorId();
                    t.createdAt = now();
                    t = store.insertTask(t);
                    Map<String, String> pl = new LinkedHashMap<>();
                    pl.put("step", String.valueOf(s.stepNo()));
                    if (a.userId() != null) pl.put("assignee", String.valueOf(a.userId()));
                    if (a.teamCode() != null) pl.put("team", a.teamCode());
                    audit(a.fallback() ? "ASSIGNEE_FALLBACK" : "TASK_CREATED", null, "SYSTEM", p.id, t.id, pl);
                    out.add(t);
                }
            }
            return out;
        });

        // 3) ON_ACTIVATE (ex FM_EXPOST): notifiche ecc. Puo' chiedere SKIP_TASK.
        ProcessVersion v = defs.version(p.versionId);
        List<TaskInstance> open = new ArrayList<>();
        for (TaskInstance t : created) {
            if (runActivateHooks(p, t, v.task(t.taskDefId))) open.add(t);
        }
        return open;
    }

    /** @return true se il task resta aperto, false se un handler ne ha chiesto lo skip. */
    private boolean runActivateHooks(ProcessInstance p, TaskInstance t, TaskDef td) {
        for (HookDef h : td.hooks(HookEvent.ON_ACTIVATE)) {
            Executed ex = invoke(p, t, Contract.TASK_ACTION, HookEvent.ON_ACTIVATE, h.handlerCode(), h.config(),
                    null, Actor.systemActor(), td);
            // un ON_ACTIVATE fallito non blocca (oggi: mail non inviata); resta in audit
            if (ex.result().status() == HandlerResult.Status.OK
                    && ex.result().directives().stream().anyMatch(d -> d instanceof SkipTask)) {
                tx.run(() -> {
                    TaskInstance x = requireTask(t.id);
                    x.status = TaskStatus.EXECUTING;
                    x.action = Action.OK.name();
                    store.updateTask(x);
                    audit("TASK_SKIPPED", null, "SYSTEM", p.id, t.id, Map.of("handler", h.handlerCode()));
                });
                complete(t.id, List.of(), ex.executionId());
                tx.run(() -> {
                    TaskInstance x = requireTask(t.id);
                    x.status = TaskStatus.SKIPPED;
                    store.updateTask(x);
                });
                return false;
            }
        }
        return true;
    }

    private List<Assignee> resolveAssignees(ProcessInstance p, TaskDef td, Activation act) {
        List<Assignee> out = new ArrayList<>();
        switch (td.assigneeMode()) {
            case STARTER -> out.add(person(p.startedBy, td, null));
            case ACTOR -> out.add(person(act.actorId() != null ? act.actorId() : p.startedBy, td, null));
            case PREVIOUS -> out.add(person(act.previousAssigneeId() != null ? act.previousAssigneeId() : p.startedBy, td, null));
            case RULES -> resolver.resolve(new AssignmentResolver.Query(
                            p.processTypeCode, td.roleCode(), documentContext(p), p.value, today()))
                    .ifPresent(a -> out.add(person(a.userId(), td, a.teamCode())));
            case HANDLER -> {
                Executed ex = invoke(p, null, Contract.ASSIGNEE, null, td.assigneeHandlerCode(), Map.of(),
                        null, Actor.systemActor(), td);
                if (ex.result().status() == HandlerResult.Status.OK) {
                    for (UserRef u : ex.result().users()) {
                        Optional<User> found = u.idType() == UserRef.IdType.EWF
                                ? users.byLogin(u.userId()) : users.bySapUser(u.userId());
                        found.filter(User::active).ifPresentOrElse(
                                x -> out.add(person(x.id(), td, null)),
                                () -> auditTx("ASSIGNEE_NOT_MAPPED", null, "SYSTEM", p.id, null,
                                        Map.of("idType", u.idType().name(), "user", u.userId())));
                    }
                }
            }
        }
        // utenti non attivi o inesistenti: scartati
        out.removeIf(a -> a.userId() != null && users.byId(a.userId()).filter(User::active).isEmpty());
        out.removeIf(a -> a.userId() == null && a.teamCode() == null);
        if (out.isEmpty()) out.add(new Assignee(null, config.fallbackTeamCode(), true));
        return out;
    }

    private Assignee person(Long userId, TaskDef td, String ruleTeam) {
        String team = td.teamTask() ? (td.teamCode() != null ? td.teamCode() : ruleTeam)
                : (userId == null ? ruleTeam : null);
        return new Assignee(userId, team, false);
    }

    private void beginCompletion(long processId) {
        tx.run(() -> {
            ProcessInstance p = requireProcess(processId);
            p.status = ProcessStatus.COMPLETING;
            store.updateProcess(p);
            audit("PROCESS_COMPLETING", null, "SYSTEM", p.id, null, Map.of());
        });
        runCompletion(processId);
    }

    /** Azione di fine processo PRIMA di dichiararlo concluso (ex FM_END_PROC, ora non piu' "dopo"). */
    private void runCompletion(long processId) {
        ProcessInstance p = requireProcess(processId);
        ProcessVersion v = defs.version(p.versionId);
        Optional<String> end = v.endHandlerCode();
        if (end.isPresent()) {
            Executed ex = invoke(p, null, Contract.PROCESS_END, null, end.get(), Map.of(), "APPROVED", Actor.systemActor(), null);
            if (ex.result().status() != HandlerResult.Status.OK) {
                tx.run(() -> {
                    ProcessInstance x = requireProcess(processId);
                    x.status = ProcessStatus.ERROR;
                    x.statusReason = (ex.result().status() == HandlerResult.Status.UNKNOWN ? REASON_UNKNOWN : REASON_END_FAILED)
                            + ": " + Objects.toString(ex.result().message(), "");
                    store.updateProcess(x);
                    audit("PROCESS_END_FAILED", null, "SYSTEM", x.id, null,
                            Map.of("execution", String.valueOf(ex.executionId())));
                });
                return;
            }
        }
        finish(processId);
    }

    private void finish(long processId) {
        tx.run(() -> {
            ProcessInstance p = requireProcess(processId);
            p.status = ProcessStatus.COMPLETED;
            p.endedAt = now();
            store.updateProcess(p);
            audit("PROCESS_COMPLETED", null, "SYSTEM", p.id, null, Map.of());
        });
    }

    // =====================================================================
    // Handler
    // =====================================================================

    private record Executed(HandlerResult result, long executionId) {}

    private Executed invoke(ProcessInstance p, TaskInstance t, Contract contract, HookEvent event, String handlerCode,
                            Map<String, String> params, String action, Actor actor, TaskDef td) {
        HandlerExecution e = tx.inTransaction(() -> {
            HandlerExecution n = new HandlerExecution();
            n.processId = p.id;
            n.taskId = t == null ? null : t.id;
            n.handlerCode = handlerCode;
            n.event = event != null ? event.name() : contract.name();
            n.status = HandlerExecution.Status.RUNNING;
            n.startedAt = now();
            return store.insertExecution(n);
        });

        HandlerResult r;
        Optional<Handler> h = handlers.find(handlerCode);
        if (h.isEmpty()) {
            r = HandlerResult.ko("Handler non registrato: " + handlerCode);
        } else {
            HandlerContext ctx = new HandlerContext(e.idempotencyKey, contract, event, action,
                    new ProcessRef(p.id, p.processTypeCode, p.docType, p.documentKey, p.backendCode, p.correlationKey, p.value),
                    t == null ? null : new TaskRef(t.id, t.stepNo, t.subopNo, t.roleCode, td == null ? null : td.taskParam()),
                    actor, params);
            try {
                r = h.get().execute(ctx);
                if (r == null) r = HandlerResult.unknown("Nessuna risposta dall'handler");
            } catch (RuntimeException ex) {
                // timeout/rete/dump: non sappiamo se l'azione e' avvenuta
                r = HandlerResult.unknown(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }

        HandlerResult result = r;
        tx.run(() -> {
            e.status = switch (result.status()) {
                case OK -> HandlerExecution.Status.OK;
                case KO -> HandlerExecution.Status.KO;
                case UNKNOWN -> HandlerExecution.Status.UNKNOWN;
            };
            e.message = result.message();
            e.directives = result.directives().isEmpty() ? null : result.directives().toString();
            e.endedAt = now();
            store.updateExecution(e);
            for (DocRef d : result.documents())
                store.insertDocument(new GeneratedDocument(p.id, e.taskId, e.id, d.docCat(), d.docNumber(), d.docYear(), d.companyCode()));
            Map<String, String> pl = new LinkedHashMap<>();
            pl.put("handler", handlerCode);
            pl.put("event", e.event);
            pl.put("status", e.status.name());
            if (result.message() != null) pl.put("message", result.message());
            if (result.replayed()) pl.put("replayed", "true");
            audit("HANDLER_EXECUTED", actor.userId(), "HANDLER", p.id, e.taskId, pl);
        });
        return new Executed(result, e.id);
    }

    private void applyDirectives(ProcessInstance p, List<Directive> directives, Long execId) {
        for (Directive d : directives) {
            switch (d) {
                case SkipStep s -> {
                    store.putStepState(new StepState(p.id, s.stepNo(), true, "HANDLER", execId));
                    audit("STEP_SKIP_REQUESTED", null, "HANDLER", p.id, null, Map.of("step", String.valueOf(s.stepNo())));
                }
                case SetValue sv -> {
                    ProcessInstance x = requireProcess(p.id);
                    x.value = sv.value();
                    store.updateProcess(x);
                    audit("VALUE_SET", null, "HANDLER", p.id, null, Map.of("value", sv.value().toString()));
                }
                case SkipTask st -> { /* rilevante solo in ON_ACTIVATE */ }
            }
        }
    }

    // =====================================================================
    // Supporto
    // =====================================================================

    /** @return true se l'attore agisce come sostituto. */
    private boolean authorize(long actorId, TaskInstance t, ProcessInstance p, TaskDef td, boolean checkWeight) {
        requireActiveUser(actorId);
        LocalDate today = today();
        boolean asSubst = false;
        boolean ok = Objects.equals(t.assigneeId, actorId);
        if (!ok && t.assigneeId != null && users.isSubstitute(actorId, t.assigneeId, today)) {
            ok = true;
            asSubst = true;
        }
        if (!ok && t.teamCode != null && users.teamMembers(t.teamCode, today).contains(actorId)) ok = true;
        if (!ok)
            throw EngineException.of(Code.NOT_AUTHORIZED, "L'utente %d non puo' agire sul task %d", actorId, t.id);
        if (t.claimedById != null && t.claimedById != actorId)
            throw EngineException.of(Code.NOT_AUTHORIZED, "Task %d in carico a un altro utente", t.id);
        if (checkWeight) {
            int w = users.effectiveWeight(actorId, p.processTypeCode);
            if (w < td.requiredWeight())
                throw EngineException.of(Code.WEIGHT_TOO_LOW, "Peso %d insufficiente (richiesto %d)", w, td.requiredWeight());
        }
        return asSubst;
    }

    /** Il lock ottimistico perso diventa un errore comprensibile per l'utente. */
    private static <T> T guarded(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeStore.OptimisticLockException e) {
            throw new EngineException(Code.CONCURRENT_UPDATE, "Dato modificato da un altro utente, ricaricare: " + e.getMessage());
        }
    }

    private void reopen(long taskId, String auditType, String message) {
        tx.run(() -> {
            TaskInstance t = requireTask(taskId);
            t.status = TaskStatus.OPEN;
            t.action = null;
            t.actedById = null;
            t.actedAsSubstitute = false;
            store.updateTask(t);
            audit(auditType, null, "SYSTEM", t.processId, t.id,
                    message == null ? Map.of() : Map.of("message", message));
        });
    }

    private void markUnknown(long processId, long execId, String message) {
        tx.run(() -> {
            ProcessInstance p = requireProcess(processId);
            p.status = ProcessStatus.ERROR;
            p.statusReason = REASON_UNKNOWN + ":" + execId;
            store.updateProcess(p);
            audit("HANDLER_UNKNOWN", null, "SYSTEM", p.id, null,
                    Map.of("execution", String.valueOf(execId), "message", Objects.toString(message, "")));
        });
    }

    private void cancel(List<TaskInstance> tasks, long processId, String reason) {
        for (TaskInstance o : tasks) {
            o.status = TaskStatus.CANCELLED;
            o.closedAt = now();
            store.updateTask(o);
            audit("TASK_CANCELLED", null, "SYSTEM", processId, o.id, Map.of("reason", reason));
        }
    }

    private ProcessInstance refreshValue(long processId) {
        ProcessInstance p = requireProcess(processId);
        Optional<Money> fresh = documentValue(p.docType, p.documentKey);
        if (fresh.isPresent() && !fresh.get().equals(p.value)) {
            return tx.inTransaction(() -> {
                ProcessInstance x = requireProcess(processId);
                Money old = x.value;
                x.value = fresh.get();
                store.updateProcess(x);
                audit("VALUE_CHANGED", null, "SYSTEM", x.id, null,
                        Map.of("from", String.valueOf(old), "to", fresh.get().toString()));
                return x;
            });
        }
        return p;
    }

    private Optional<Money> documentValue(String docType, String key) {
        try {
            return adapters.find(docType).flatMap(a -> a.value(key));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Map<String, String> documentContext(ProcessInstance p) {
        try {
            return adapters.find(p.docType).map(a -> a.context(p.documentKey)).orElse(Map.of());
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private boolean belowMinimum(Money value, Money min) {
        if (min == null || value == null) return false;
        Money v = value.currency().equals(min.currency()) ? value : currency.convert(value, min.currency(), today());
        return v.amount().compareTo(min.amount()) < 0;
    }

    private Actor actorOf(long userId, boolean asSubst) {
        User u = requireActiveUser(userId);
        return new Actor(u.id(), u.login(), u.sapUser(), asSubst, false);
    }

    private User requireActiveUser(long id) {
        return users.byId(id).filter(User::active)
                .orElseThrow(() -> EngineException.of(Code.NOT_AUTHORIZED, "Utente %d inesistente o non attivo", id));
    }

    private TaskInstance requireTask(long id) {
        return store.task(id).orElseThrow(() -> EngineException.of(Code.NOT_FOUND, "Task %d inesistente", id));
    }

    private ProcessInstance requireProcess(long id) {
        return store.process(id).orElseThrow(() -> EngineException.of(Code.NOT_FOUND, "Processo %d inesistente", id));
    }

    private ProcessInstance reloadProcess(long id) { return requireProcess(id); }

    private void audit(String type, Long actorId, String kind, Long processId, Long taskId, Map<String, String> payload) {
        audit.append(new AuditSink.AuditEvent(now(), type, actorId, kind, processId, taskId, payload));
    }

    private void auditTx(String type, Long actorId, String kind, Long processId, Long taskId, Map<String, String> payload) {
        tx.run(() -> audit(type, actorId, kind, processId, taskId, payload));
    }

    private Instant now() { return clock.instant(); }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), config.zone()); }
}
