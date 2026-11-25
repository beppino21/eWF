package it.eone.ewf.s4cloud;

import it.eone.connect.scheduler.Job;
import it.eone.connect.scheduler.JobContext;
import it.eone.connect.scheduler.JobResult;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.Requests.EventResult.EventStatus;
import it.eone.ewf.engine.StartQueueProcessor;
import it.eone.ewf.engine.WorkflowEngine;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Job eone-connect "ewf.s4-po-poll": legge gli OdA modificati in S/4 Public e li passa al motore (D23).
 *
 * Parametri del job (eone_connect.job.params, modificabili senza riavvio):
 *   batch      OdA letti per giro                         (default 50)
 *   maxRounds  giri consecutivi se ne arrivano "batch"     (default 10)
 *   attempts   tentativi su un evento prima di superarlo   (default 20)
 *   since      punto di partenza la prima volta, ISO UTC  (default: l'ora del primo giro)
 *
 * Esito come per la coda on-premise: IDLE se non c'era niente, ERROR se un evento e' fallito,
 * WARNING per gli avvisi (tra cui le decisioni prese in SAP prima della fine dell'approvazione eWF).
 */
public final class PurchaseOrderPollJob implements Job {

    public static final String KEY = "ewf.s4-po-poll";
    public static final String CODE = "EWF_S4_PO_POLL";

    private final WorkflowEngine engine;
    private final PurchaseOrderApi api;
    private final Supplier<PurchaseOrderPoller.Watermark> watermark;
    private final String backend;
    private final Clock clock;

    public PurchaseOrderPollJob(WorkflowEngine engine, PurchaseOrderApi api, Supplier<PurchaseOrderPoller.Watermark> watermark,
                                String backend, Clock clock) {
        this.engine = engine;
        this.api = api;
        this.watermark = watermark;
        this.backend = backend;
        this.clock = clock;
    }

    @Override
    public JobResult run(JobContext ctx) {
        int batch = Integer.parseInt(ctx.param("batch", "50"));
        int maxRounds = Integer.parseInt(ctx.param("maxRounds", "10"));
        int attempts = Integer.parseInt(ctx.param("attempts", "20"));
        String since = ctx.param("since", "");
        Instant initial = since.isBlank() ? clock.instant() : Instant.parse(since);
        PurchaseOrderPoller poller = new PurchaseOrderPoller(api, watermark.get(), backend, initial, attempts);
        StartQueueProcessor processor = new StartQueueProcessor(engine, poller, batch);
        return drain(processor::runOnce, batch, maxRounds, ctx::stopRequested);
    }

    static JobResult drain(Supplier<List<EventResult>> round, int batch, int maxRounds, java.util.function.BooleanSupplier stop) {
        long done = 0, warnings = 0, errors = 0;
        String firstError = null, firstWarning = null;
        List<String> details = new java.util.ArrayList<>();
        for (int n = 0; n < maxRounds && !stop.getAsBoolean(); n++) {
            List<EventResult> results = round.get();
            for (EventResult r : results) {
                String line = r.queueId() + " [" + r.status() + "] " + r.message()
                        + (r.processId() == null ? "" : " (processo " + r.processId() + ")");
                System.out.println("[" + CODE + "] " + line);
                if (details.size() < 5) details.add(line);
                if (r.status() == EventStatus.ERROR) {
                    errors++;
                    if (firstError == null) firstError = r.queueId() + ": " + r.message();
                } else if (r.status() == EventStatus.WARNING) {
                    warnings++;
                    if (firstWarning == null) firstWarning = r.queueId() + ": " + r.message();
                } else done++;
            }
            if (errors > 0 || results.size() < batch) break;
        }
        if (done + warnings + errors == 0) return JobResult.idle();
        JobResult r = errors > 0 ? JobResult.error(errors + " eventi in errore, il primo " + firstError)
                : warnings > 0 ? JobResult.warning(warnings + " avvisi, il primo " + firstWarning)
                : JobResult.ok(String.join("; ", details) + (done > details.size() ? "; ..." : ""));
        return r.with("elaborati", done).with("avvisi", warnings).with("errori", errors);
    }
}
