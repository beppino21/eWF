package it.eone.ewf.sap;

import it.eone.connect.scheduler.Job;
import it.eone.connect.scheduler.JobContext;
import it.eone.connect.scheduler.JobResult;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.Requests.EventResult.EventStatus;
import it.eone.ewf.engine.StartQueueProcessor;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.spi.StartSource;

import java.util.List;

/**
 * Job eone-connect "ewf.start-queue": legge la coda degli avvii /EWF/START_Q e la passa al motore.
 *
 * Parametri del job (eone_connect.job.params, modificabili senza riavvio):
 *   batch      eventi letti per giro            (default 50)
 *   maxRounds  giri consecutivi se la coda e' piena (default 10)
 *   attempts   tentativi prima che SAP marchi l'evento in errore (default 20)
 *
 * Esito: IDLE se la coda era vuota (nessuna riga di storico), ERROR se almeno un evento e' fallito
 * (il cruscotto lo deve mostrare), WARNING se ci sono avvisi (es. assegnato al team di riserva), altrimenti OK.
 */
public final class StartQueueJob implements Job {

    public static final String KEY = "ewf.start-queue";
    public static final String CODE = "EWF_START_QUEUE";

    private final WorkflowEngine engine;
    private final String destination;

    public StartQueueJob(WorkflowEngine engine, String destination) {
        this.engine = engine;
        this.destination = destination;
    }

    @Override
    public JobResult run(JobContext ctx) {
        int batch = Integer.parseInt(ctx.param("batch", "50"));
        int maxRounds = Integer.parseInt(ctx.param("maxRounds", "10"));
        int attempts = Integer.parseInt(ctx.param("attempts", "20"));
        StartQueueProcessor processor = new StartQueueProcessor(engine, source(attempts), batch);
        return drain(processor::runOnce, batch, maxRounds, ctx::stopRequested);
    }

    StartSource source(int attempts) { return new SapStartQueue(destination, attempts); }

    /** Separato da run per poterlo provare senza SAP. */
    static JobResult drain(java.util.function.Supplier<List<EventResult>> round, int batch, int maxRounds, java.util.function.BooleanSupplier stop) {
        long done = 0, warnings = 0, errors = 0;
        String firstError = null, firstWarning = null;
        for (int n = 0; n < maxRounds && !stop.getAsBoolean(); n++) {
            List<EventResult> results = round.get();
            for (EventResult r : results) {
                if (r.status() == EventStatus.ERROR) {
                    errors++;
                    if (firstError == null) firstError = r.queueId() + ": " + r.message();
                } else if (r.status() == EventStatus.WARNING) {
                    warnings++;
                    if (firstWarning == null) firstWarning = r.queueId() + ": " + r.message();
                } else done++;
            }
            // meno eventi del lotto: la coda e' vuota (o restano solo eventi bloccati dietro un errore)
            if (results.size() < batch) break;
        }
        if (done + warnings + errors == 0) return JobResult.idle();
        JobResult r = errors > 0 ? JobResult.error(errors + " eventi in errore, il primo " + firstError)
                : warnings > 0 ? JobResult.warning(warnings + " avvisi, il primo " + firstWarning)
                : JobResult.ok(null);
        return r.with("elaborati", done).with("avvisi", warnings).with("errori", errors);
    }
}
