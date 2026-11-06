package it.eone.ewf.engine.model;

import java.time.Instant;
import java.util.UUID;

/** Istanza di processo (ex PROCDOC + PROCRUN). Mutabile solo dentro il motore. */
public final class ProcessInstance {
    public long id;
    public long versionId;
    public String processTypeCode;
    public String docType;
    public String documentKey;
    public String backendCode;
    public ProcessStatus status = ProcessStatus.RUNNING;
    public String statusReason;
    public Money value;
    public long startedBy;
    public Instant startedAt;
    public Long endedBy;
    public Instant endedAt;
    public UUID correlationKey = UUID.randomUUID();
    public int rowVersion;

    public ProcessInstance copy() {
        ProcessInstance c = new ProcessInstance();
        c.id = id; c.versionId = versionId; c.processTypeCode = processTypeCode; c.docType = docType;
        c.documentKey = documentKey; c.backendCode = backendCode; c.status = status; c.statusReason = statusReason;
        c.value = value; c.startedBy = startedBy; c.startedAt = startedAt; c.endedBy = endedBy; c.endedAt = endedAt;
        c.correlationKey = correlationKey; c.rowVersion = rowVersion;
        return c;
    }
}
