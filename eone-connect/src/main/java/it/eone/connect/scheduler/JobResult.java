package it.eone.connect.scheduler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Esito di un'esecuzione: finisce nello storico (job_run) con i conteggi.
 * IDLE = non c'era niente da fare: il giro non entra nello storico, si aggiorna solo job.last_idle_at
 * (un job ogni 30 secondi su una coda quasi sempre vuota altrimenti scriverebbe migliaia di righe inutili).
 */
public record JobResult(Status status, String message, Map<String, Long> counts) {
    public enum Status { OK, WARNING, ERROR, IDLE }

    public JobResult {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }

    public static JobResult idle() { return new JobResult(Status.IDLE, null, Map.of()); }
    public static JobResult ok(String message) { return new JobResult(Status.OK, message, Map.of()); }
    public static JobResult warning(String message) { return new JobResult(Status.WARNING, message, Map.of()); }
    public static JobResult error(String message) { return new JobResult(Status.ERROR, message, Map.of()); }

    public JobResult with(String counter, long value) {
        Map<String, Long> c = new LinkedHashMap<>(counts);
        c.put(counter, value);
        return new JobResult(status, message, c);
    }
}
