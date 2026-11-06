package it.eone.ewf.engine.model;

/** Tipo processo (ex /EWF/PROCESSO). */
public record ProcessType(String code, String description, String docType,
                          boolean suspended, boolean autoOkFirst, boolean noFirstStep) {}
