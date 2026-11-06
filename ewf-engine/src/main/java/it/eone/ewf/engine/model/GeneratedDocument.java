package it.eone.ewf.engine.model;

/** Documento creato in SAP da un handler (ex TASKDOCUM). */
public record GeneratedDocument(long processId, Long taskId, Long executionId,
                                String docCat, String docNumber, String docYear, String companyCode) {}
