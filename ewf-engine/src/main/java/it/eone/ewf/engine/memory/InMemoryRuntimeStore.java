package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.RuntimeStore;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime in memoria. Restituisce sempre copie (come farebbe un DB) e applica
 * il lock ottimistico su rowVersion. Gli id partono da 8000000001 come nello schema.
 */
public final class InMemoryRuntimeStore implements RuntimeStore {
    private final AtomicLong processSeq = new AtomicLong(8_000_000_000L);
    private final AtomicLong taskSeq = new AtomicLong(8_000_000_000L);
    private final AtomicLong execSeq = new AtomicLong();
    private final Map<Long, ProcessInstance> processes = new LinkedHashMap<>();
    private final Map<Long, TaskInstance> tasks = new LinkedHashMap<>();
    private final Map<Long, Map<Integer, StepState>> states = new HashMap<>();
    private final Map<Long, HandlerExecution> executions = new LinkedHashMap<>();
    private final List<GeneratedDocument> documents = new ArrayList<>();
    private final List<ProcessLink> links = new ArrayList<>();

    @Override public synchronized ProcessInstance insertProcess(ProcessInstance p) {
        p.id = processSeq.incrementAndGet();
        p.rowVersion = 0;
        processes.put(p.id, p.copy());
        return p.copy();
    }

    @Override public synchronized void updateProcess(ProcessInstance p) {
        ProcessInstance cur = processes.get(p.id);
        if (cur == null || cur.rowVersion != p.rowVersion)
            throw new OptimisticLockException("process " + p.id);
        p.rowVersion++;
        processes.put(p.id, p.copy());
    }

    /** In memoria le transazioni sono gia' serializzate da LocalTransactions. */
    @Override public void lockProcess(long processId) { }

    @Override public synchronized Optional<ProcessInstance> process(long id) {
        return Optional.ofNullable(processes.get(id)).map(ProcessInstance::copy);
    }

    @Override public synchronized boolean hasOpenProcess(String type, String docType, String key, String backend) {
        return processes.values().stream().anyMatch(p -> p.processTypeCode.equals(type) && p.docType.equals(docType)
                && p.documentKey.equals(key) && Objects.equals(p.backendCode, backend) && p.status.isOpen());
    }

    @Override public synchronized TaskInstance insertTask(TaskInstance t) {
        t.id = taskSeq.incrementAndGet();
        t.rowVersion = 0;
        tasks.put(t.id, t.copy());
        return t.copy();
    }

    @Override public synchronized void updateTask(TaskInstance t) {
        TaskInstance cur = tasks.get(t.id);
        if (cur == null || cur.rowVersion != t.rowVersion)
            throw new OptimisticLockException("task " + t.id);
        t.rowVersion++;
        tasks.put(t.id, t.copy());
    }

    @Override public synchronized Optional<TaskInstance> task(long id) {
        return Optional.ofNullable(tasks.get(id)).map(TaskInstance::copy);
    }

    @Override public synchronized List<TaskInstance> tasksOf(long processId) {
        return tasks.values().stream().filter(t -> t.processId == processId).map(TaskInstance::copy).toList();
    }

    @Override public synchronized List<TaskInstance> openTasksFor(Set<Long> assignees, Set<String> teams) {
        return tasks.values().stream()
                .filter(t -> t.status == TaskStatus.OPEN)
                .filter(t -> (t.assigneeId != null && assignees.contains(t.assigneeId))
                        || (t.teamCode != null && teams.contains(t.teamCode)))
                .map(TaskInstance::copy).toList();
    }

    @Override public synchronized Map<Integer, StepState> stepStates(long processId) {
        return Map.copyOf(states.getOrDefault(processId, Map.of()));
    }

    @Override public synchronized void putStepState(StepState s) {
        states.computeIfAbsent(s.processId(), k -> new HashMap<>()).put(s.stepNo(), s);
    }

    @Override public synchronized void clearStepStates(long processId, String reason) {
        Map<Integer, StepState> m = states.get(processId);
        if (m != null) m.values().removeIf(s -> s.reason().equals(reason));
    }

    @Override public synchronized HandlerExecution insertExecution(HandlerExecution e) {
        e.id = execSeq.incrementAndGet();
        executions.put(e.id, e);
        return e;
    }

    @Override public synchronized void updateExecution(HandlerExecution e) { executions.put(e.id, e); }

    @Override public synchronized Optional<HandlerExecution> execution(long id) {
        return Optional.ofNullable(executions.get(id));
    }

    @Override public synchronized List<HandlerExecution> executionsOf(long processId) {
        return executions.values().stream().filter(e -> e.processId == processId).toList();
    }

    @Override public synchronized void insertDocument(GeneratedDocument d) { documents.add(d); }

    @Override public synchronized List<GeneratedDocument> documentsOf(long processId) {
        return documents.stream().filter(d -> d.processId() == processId).toList();
    }

    @Override public synchronized void insertLink(ProcessLink l) { links.add(l); }

    @Override public synchronized List<ProcessLink> links(long processId) {
        return links.stream().filter(l -> l.parentId() == processId || l.childId() == processId).toList();
    }
}
