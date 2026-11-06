package it.eone.ewf.engine.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Versione immutabile della definizione di un processo. */
public final class ProcessVersion {
    private final long id;
    private final String processTypeCode;
    private final int versionNo;
    private final String endHandlerCode;
    private final Map<Integer, StepDef> steps;
    private final Map<Long, TaskDef> tasks;
    private final Map<Long, Integer> stepOfTask;

    public ProcessVersion(long id, String processTypeCode, int versionNo, String endHandlerCode, List<StepDef> steps) {
        this.id = id;
        this.processTypeCode = processTypeCode;
        this.versionNo = versionNo;
        this.endHandlerCode = endHandlerCode;
        this.steps = steps.stream().collect(Collectors.toUnmodifiableMap(StepDef::stepNo, Function.identity()));
        this.tasks = steps.stream().flatMap(s -> s.tasks().stream())
                .collect(Collectors.toUnmodifiableMap(TaskDef::id, Function.identity()));
        this.stepOfTask = steps.stream().flatMap(s -> s.tasks().stream().map(t -> Map.entry(t.id(), s.stepNo())))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        long firsts = steps.stream().filter(StepDef::first).count();
        if (firsts != 1) throw new IllegalArgumentException("Serve esattamente un primo step, trovati " + firsts);
        for (StepDef s : steps) {
            if (Integer.valueOf(s.stepNo()).equals(s.nextOk()) || Integer.valueOf(s.stepNo()).equals(s.nextKo()))
                throw new IllegalArgumentException("Step " + s.stepNo() + " punta a se stesso");
            if (s.tasks().isEmpty())
                throw new IllegalArgumentException("Step " + s.stepNo() + " senza task");
        }
    }

    public long id() { return id; }
    public String processTypeCode() { return processTypeCode; }
    public int versionNo() { return versionNo; }
    public Optional<String> endHandlerCode() { return Optional.ofNullable(endHandlerCode); }

    /** Step ordinati per numero. */
    public List<StepDef> steps() {
        return steps.values().stream().sorted(java.util.Comparator.comparingInt(StepDef::stepNo)).toList();
    }

    public StepDef firstStep() {
        return steps.values().stream().filter(StepDef::first).findFirst().orElseThrow();
    }
    public Optional<StepDef> step(Integer stepNo) {
        return stepNo == null ? Optional.empty() : Optional.ofNullable(steps.get(stepNo));
    }
    public TaskDef task(long taskDefId) {
        TaskDef t = tasks.get(taskDefId);
        if (t == null) throw new IllegalArgumentException("task_def " + taskDefId + " non in versione " + id);
        return t;
    }
    public int stepOf(long taskDefId) { return stepOfTask.get(taskDefId); }
}
