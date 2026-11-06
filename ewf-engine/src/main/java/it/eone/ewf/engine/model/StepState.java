package it.eone.ewf.engine.model;

/** Stato di uno step nella singola istanza (ex Z_WF_SKIP). */
public record StepState(long processId, int stepNo, boolean skipped, String reason, Long sourceExecutionId) {}
