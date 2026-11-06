package it.eone.ewf.engine.model;

/**
 * RULES ex /EWF/WF_USER_TASK, HANDLER = FM di assegnazione specifica,
 * STARTER ex SY_START, ACTOR ex SY_UNAME, PREVIOUS ex >>>SKIP_TASK.
 */
public enum AssigneeMode { RULES, HANDLER, STARTER, ACTOR, PREVIOUS }
