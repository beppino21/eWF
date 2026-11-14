package it.eone.ewf.engine.spi;

import java.time.Instant;

/** Notifica registrata dal motore e non ancora trasformata in messaggio (vedi ewf_core.notification). */
public record PendingNotification(long id, String kind, long processId, long taskId, Instant createdAt) {
    public static final String TASK_ACTIVATED = "TASK_ACTIVATED";
}
