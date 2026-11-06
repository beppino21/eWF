package it.eone.ewf.engine.spi;

import java.time.Instant;
import java.util.Map;

/** Audit append-only. */
public interface AuditSink {

    record AuditEvent(Instant at, String type, Long actorId, String actorKind,
                      Long processId, Long taskId, Map<String, String> payload) {}

    void append(AuditEvent event);
}
