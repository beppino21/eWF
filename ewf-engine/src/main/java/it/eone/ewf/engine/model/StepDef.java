package it.eone.ewf.engine.model;

import java.util.List;

/**
 * Step del processo. nextOk/nextKo null = fine processo (ex 99999).
 * minAmount: lo step serve solo se il valore del documento e' >= soglia (ex Z_WF_LIMIT).
 */
public record StepDef(int stepNo, boolean first, JoinPolicy join, Integer nextOk, Integer nextKo,
                      Money minAmount, List<TaskDef> tasks) {
    public StepDef {
        tasks = List.copyOf(tasks);
        if (join == null) join = JoinPolicy.ALL;
    }
}
