package it.eone.ewf.engine.model;

import java.util.List;

/**
 * Step del processo. nextOk/nextKo null = fine processo (ex 99999).
 * minAmount: lo step serve solo se il valore del documento e' >= soglia (ex Z_WF_LIMIT).
 * option: null = step del tronco comune; valorizzato = variante dello step valida solo se
 * quell'opzione e' tra le scelte del processo (sostituisce lo step del tronco con lo stesso numero).
 */
public record StepDef(int stepNo, boolean first, JoinPolicy join, Integer nextOk, Integer nextKo,
                      Money minAmount, List<TaskDef> tasks, String option) {
    public StepDef {
        tasks = List.copyOf(tasks);
        if (join == null) join = JoinPolicy.ALL;
        if (option != null && option.isBlank()) option = null;
    }

    /** Step del tronco comune. */
    public StepDef(int stepNo, boolean first, JoinPolicy join, Integer nextOk, Integer nextKo,
                   Money minAmount, List<TaskDef> tasks) {
        this(stepNo, first, join, nextOk, nextKo, minAmount, tasks, null);
    }

    public boolean isVariant() { return option != null; }

    /** Nodo di scelta: lo step contiene un task con opzioni. */
    public boolean isChoiceNode() { return tasks.stream().anyMatch(TaskDef::isChoice); }
}
