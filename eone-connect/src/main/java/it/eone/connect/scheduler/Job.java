package it.eone.connect.scheduler;

/**
 * Un lavoro in background. L'applicazione lo registra con una chiave (JobRegistry); la pianificazione
 * sta nel DB. Il job non deve preoccuparsi di esecuzioni concorrenti: lo scheduler garantisce che in
 * tutto il cluster ne giri una alla volta. Un'eccezione = esito ERROR, registrato nello storico.
 * Nei cicli lunghi controllare ctx.stopRequested() per fermarsi in modo pulito allo spegnimento.
 */
@FunctionalInterface
public interface Job {
    JobResult run(JobContext ctx) throws Exception;
}
