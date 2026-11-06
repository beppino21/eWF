package it.eone.ewf.engine.model;

import java.time.Instant;
import java.util.UUID;

/** Esecuzione di un handler: stato, idempotenza, riconciliazione. */
public final class HandlerExecution {
    public enum Status { PENDING, RUNNING, OK, KO, UNKNOWN }

    public long id;
    public long processId;
    public Long taskId;
    public String handlerCode;
    public String event;
    public int attempt = 1;
    public UUID idempotencyKey = UUID.randomUUID();
    public Status status = Status.PENDING;
    public String message;
    public String directives;
    public Instant startedAt;
    public Instant endedAt;
}
