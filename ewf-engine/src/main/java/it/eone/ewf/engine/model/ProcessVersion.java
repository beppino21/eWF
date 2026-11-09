package it.eone.ewf.engine.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Versione immutabile della definizione di un processo.
 *
 * Rami per opzione: lo stesso numero di step puo' esistere nel tronco comune (option = null)
 * e in varianti legate a un'opzione. Arrivato allo step N, il motore usa la variante la cui
 * opzione e' tra le scelte del processo, altrimenti lo step del tronco (override).
 */
public final class ProcessVersion {

    /** Limite all'esplorazione dei percorsi in validazione (combinazioni step/scelte). */
    static final int MAX_PATH_STATES = 20_000;

    private final long id;
    private final String processTypeCode;
    private final int versionNo;
    private final String endHandlerCode;
    private final Map<String, String> endHandlerConfig;
    private final Map<Integer, List<StepDef>> steps;     // numero -> tronco e varianti
    private final Map<Long, TaskDef> tasks;
    private final Map<Long, StepDef> stepOfTask;
    private final Map<String, Integer> nodeOfOption;     // codice opzione -> step del nodo che la offre

    public ProcessVersion(long id, String processTypeCode, int versionNo, String endHandlerCode, List<StepDef> steps) {
        this(id, processTypeCode, versionNo, endHandlerCode, Map.of(), steps);
    }

    /** endHandlerConfig: parametri dell'azione di fine processo (es. Z_WF_SET_RIL=01 per il rilascio OdA). */
    public ProcessVersion(long id, String processTypeCode, int versionNo, String endHandlerCode,
                          Map<String, String> endHandlerConfig, List<StepDef> steps) {
        this.id = id;
        this.processTypeCode = processTypeCode;
        this.versionNo = versionNo;
        this.endHandlerCode = endHandlerCode;
        this.endHandlerConfig = endHandlerConfig == null ? Map.of() : Map.copyOf(endHandlerConfig);

        Map<Integer, List<StepDef>> byNo = new TreeMap<>();
        for (StepDef s : steps) {
            List<StepDef> same = byNo.computeIfAbsent(s.stepNo(), k -> new ArrayList<>());
            if (same.stream().anyMatch(o -> Objects.equals(o.option(), s.option())))
                throw new IllegalArgumentException("Step " + label(s) + " definito due volte");
            same.add(s);
        }
        byNo.replaceAll((k, l) -> List.copyOf(l));
        this.steps = Collections.unmodifiableMap(byNo);

        Map<Long, TaskDef> t = new HashMap<>();
        Map<Long, StepDef> st = new HashMap<>();
        for (StepDef s : steps)
            for (TaskDef d : s.tasks()) {
                if (t.put(d.id(), d) != null) throw new IllegalArgumentException("task_def " + d.id() + " duplicato");
                st.put(d.id(), s);
            }
        this.tasks = Map.copyOf(t);
        this.stepOfTask = Map.copyOf(st);

        long firsts = steps.stream().filter(StepDef::first).count();
        if (firsts != 1) throw new IllegalArgumentException("Serve esattamente un primo step, trovati " + firsts);
        for (StepDef s : steps) {
            if (Integer.valueOf(s.stepNo()).equals(s.nextOk()) || Integer.valueOf(s.stepNo()).equals(s.nextKo()))
                throw new IllegalArgumentException("Step " + label(s) + " punta a se stesso");
            if (s.tasks().isEmpty())
                throw new IllegalArgumentException("Step " + label(s) + " senza task");
        }

        Map<String, Integer> nodes = new HashMap<>();
        for (StepDef s : steps)
            for (TaskDef d : s.tasks())
                for (OptionDef o : d.options())
                    nodes.putIfAbsent(o.code(), s.stepNo());
        this.nodeOfOption = Map.copyOf(nodes);
    }

    public long id() { return id; }
    public String processTypeCode() { return processTypeCode; }
    public int versionNo() { return versionNo; }
    public Optional<String> endHandlerCode() { return Optional.ofNullable(endHandlerCode); }
    public Map<String, String> endHandlerConfig() { return endHandlerConfig; }

    /** Tutti gli step, tronco e varianti, ordinati per numero (tronco prima delle varianti). */
    public List<StepDef> steps() {
        return steps.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparingInt(StepDef::stepNo)
                        .thenComparing(StepDef::option, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    public StepDef firstStep() {
        return steps().stream().filter(StepDef::first).findFirst().orElseThrow();
    }

    /** Step del tronco comune con quel numero. */
    public Optional<StepDef> step(Integer stepNo) {
        if (stepNo == null) return Optional.empty();
        return steps.getOrDefault(stepNo, List.of()).stream().filter(s -> !s.isVariant()).findFirst();
    }

    /** Variante legata a un'opzione. */
    public Optional<StepDef> step(Integer stepNo, String option) {
        if (option == null) return step(stepNo);
        if (stepNo == null) return Optional.empty();
        return steps.getOrDefault(stepNo, List.of()).stream().filter(s -> option.equals(s.option())).findFirst();
    }

    /**
     * Lo step da eseguire dato l'insieme delle opzioni scelte nel processo:
     * la variante di un'opzione scelta, altrimenti il tronco.
     * @throws AmbiguousStepException se si applicano varianti di due opzioni diverse
     */
    public Optional<StepDef> resolve(Integer stepNo, Collection<String> chosen) {
        if (stepNo == null) return Optional.empty();
        List<StepDef> all = steps.getOrDefault(stepNo, List.of());
        List<StepDef> variants = all.stream().filter(s -> s.isVariant() && chosen.contains(s.option())).toList();
        if (variants.size() > 1)
            throw new AmbiguousStepException("Step " + stepNo + ": si applicano piu' varianti ("
                    + variants.stream().map(StepDef::option).collect(Collectors.joining(", ")) + ")");
        if (variants.size() == 1) return Optional.of(variants.get(0));
        return all.stream().filter(s -> !s.isVariant()).findFirst();
    }

    public TaskDef task(long taskDefId) {
        TaskDef t = tasks.get(taskDefId);
        if (t == null) throw new IllegalArgumentException("task_def " + taskDefId + " non in versione " + id);
        return t;
    }

    /** Lo step (tronco o variante) a cui appartiene il task. */
    public StepDef stepDefOf(long taskDefId) {
        StepDef s = stepOfTask.get(taskDefId);
        if (s == null) throw new IllegalArgumentException("task_def " + taskDefId + " non in versione " + id);
        return s;
    }

    public int stepOf(long taskDefId) { return stepDefOf(taskDefId).stepNo(); }

    /** Il numero dello step che offre l'opzione (il "nodo" a cui la scelta appartiene). */
    public Optional<Integer> nodeOf(String optionCode) { return Optional.ofNullable(nodeOfOption.get(optionCode)); }

    // =====================================================================
    // Validazione dei rami
    // =====================================================================

    public record Problem(String severity, Integer stepNo, String problem) {
        static Problem error(Integer step, String msg) { return new Problem("ERROR", step, msg); }
        static Problem warning(Integer step, String msg) { return new Problem("WARNING", step, msg); }
    }

    public static final class AmbiguousStepException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public AmbiguousStepException(String msg) { super(msg); }
    }

    /**
     * Controlli su opzioni e varianti, piu' l'esplorazione di tutti i percorsi possibili:
     * per ogni combinazione di scelte raggiungibile, ogni transizione deve portare a uno step
     * esistente (nel tronco o nella variante giusta) e senza ambiguita'.
     * Non considera soglie d'importo e salti chiesti dagli handler: possono solo accorciare un percorso.
     */
    public List<Problem> problems() {
        List<Problem> out = new ArrayList<>();

        Map<String, Integer> seenCodes = new HashMap<>();
        for (StepDef s : steps()) {
            boolean choice = s.isChoiceNode();
            if (choice && s.tasks().size() > 1)
                out.add(Problem.error(s.stepNo(), "nodo di scelta con piu' task: ammesso un solo task"));
            if (s.first() && s.isVariant())
                out.add(Problem.error(s.stepNo(), "il primo step non puo' essere una variante (" + s.option() + ")"));
            for (TaskDef d : s.tasks()) {
                if (d.isChoice() && d.options().size() < 2)
                    out.add(Problem.warning(s.stepNo(), "nodo di scelta con una sola opzione"));
                for (OptionDef o : d.options()) {
                    Integer prev = seenCodes.putIfAbsent(o.code(), s.stepNo());
                    if (prev != null)
                        out.add(Problem.error(s.stepNo(), "opzione " + o.code() + " gia' usata allo step " + prev
                                + ": i codici devono essere univoci nel processo"));
                }
            }
        }
        for (StepDef s : steps())
            if (s.isVariant() && !nodeOfOption.containsKey(s.option()))
                out.add(Problem.error(s.stepNo(), "variante per l'opzione " + s.option() + " che nessun nodo offre"));
        if (out.stream().anyMatch(p -> p.severity().equals("ERROR"))) return out;

        explorePaths(out);
        return out;
    }

    private record PathState(int stepNo, Map<Integer, String> choices) {}

    private void explorePaths(List<Problem> out) {
        Set<String> reported = new HashSet<>();
        Set<StepDef> reached = new HashSet<>();
        Set<PathState> visited = new HashSet<>();
        Deque<PathState> todo = new ArrayDeque<>();
        todo.push(new PathState(firstStep().stepNo(), Map.of()));
        while (!todo.isEmpty()) {
            PathState ps = todo.pop();
            if (!visited.add(ps)) continue;
            if (visited.size() > MAX_PATH_STATES) {
                out.add(Problem.warning(null, "troppe combinazioni di scelte: controllo dei percorsi interrotto"));
                return;
            }
            StepDef s;
            try {
                s = resolve(ps.stepNo(), ps.choices().values()).orElse(null);
            } catch (AmbiguousStepException e) {
                if (reported.add("A" + ps.stepNo()))
                    out.add(Problem.error(ps.stepNo(), e.getMessage() + " con le scelte " + describe(ps.choices())));
                continue;
            }
            if (s == null) {
                if (reported.add("M" + ps.stepNo() + ps.choices()))
                    out.add(Problem.error(ps.stepNo(), "step inesistente per le scelte " + describe(ps.choices())));
                continue;
            }
            reached.add(s);
            if (s.isChoiceNode()) {
                for (OptionDef o : s.tasks().get(0).options()) {
                    Map<Integer, String> next = new TreeMap<>(ps.choices());
                    next.put(s.stepNo(), o.code());
                    Integer target = o.reject() ? s.nextKo() : s.nextOk();
                    if (target != null) todo.push(new PathState(target, Map.copyOf(next)));
                }
            } else {
                if (s.nextOk() != null) todo.push(new PathState(s.nextOk(), ps.choices()));
                if (s.nextKo() != null) todo.push(new PathState(s.nextKo(), ps.choices()));
            }
        }
        for (StepDef s : steps())
            if (s.isVariant() && !reached.contains(s))
                out.add(Problem.warning(s.stepNo(), "variante " + s.option() + " mai raggiunta da nessun percorso"));
    }

    private static String describe(Map<Integer, String> choices) {
        if (choices.isEmpty()) return "(nessuna)";
        return choices.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ", "{", "}"));
    }

    private static String label(StepDef s) {
        return s.stepNo() + (s.isVariant() ? "/" + s.option() : "");
    }
}
