package it.eone.ewf.ewfserver.app;

import it.eone.connect.db.Db;
import it.eone.connect.mail.MailOutbox;
import it.eone.connect.mail.MailSendJob;
import it.eone.connect.mail.SmtpConfig;
import it.eone.connect.mail.SmtpTransport;
import it.eone.connect.scheduler.JobDefinition;
import it.eone.connect.scheduler.JobRegistry;
import it.eone.connect.scheduler.JobRun;
import it.eone.connect.scheduler.JobResult;
import it.eone.connect.scheduler.Scheduler;
import it.eone.ewf.ewfserver.config.AppConfig;
import it.eone.ewf.notify.DigestJob;
import it.eone.ewf.notify.NotifyJob;
import it.eone.ewf.sap.StartQueueJob;
import it.eone.ewf.s4cloud.PurchaseOrderPollJob;
import it.eone.ewf.s4cloud.SyncWatermark;
import it.eone.connect.sync.SyncState;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Avvia e ferma i job di eWF insieme all'applicazione web (registrato in web.xml).
 *
 * All'avvio di Tomcat crea i servizi (EwfApp) e lo scheduler di eone-connect con i job:
 *   EWF_START_QUEUE  coda degli avvii /EWF/START_Q (solo se SAP e' collegato)   ogni 30 secondi
 *   EWF_S4_PO_POLL   OdA modificati in S/4 Public (solo se c'e' un S4_PUBLIC)  ogni minuto
 *   EWF_NOTIFY       avvisi immediati dei task                                  ogni minuto
 *   EWF_MAIL_DIGEST  riepilogo periodico                                        giorni feriali 07:15
 *   EONE_MAIL_SEND   spedizione delle mail in coda (solo se EONE_SMTP_HOST c'e') ogni minuto
 * I job si creano in eone_connect.job solo se mancano: pianificazione e attivazione si cambiano poi li'.
 * Con piu' Tomcat sullo stesso database ogni job gira su uno solo (lock di PostgreSQL).
 *
 * Configurazione:
 *   EWF_JOBS_ENABLED  false = nessun job su questa istanza (es. un secondo ambiente di sviluppo
 *                     collegato allo stesso database). Default true.
 *   EWF_APP_BASE_URL  indirizzo dell'applicazione per i link nelle mail (es. https://server/ewfserver).
 *   EONE_SMTP_*       posta in uscita (vedi SmtpConfig di eone-connect); senza, le mail restano in coda.
 *
 * Se qualcosa manca (es. schema eone_connect non installato) l'applicazione parte lo stesso, senza job,
 * e il motivo e' scritto nel log di Tomcat.
 */
public final class JobHost implements ServletContextListener {

    /** Istanza attiva (una per applicazione): serve al pulsante "Aggiorna" per anticipare la coda. */
    private static volatile JobHost active;

    private Scheduler scheduler;
    private boolean startQueueActive;
    /** Job che legge gli eventi dei documenti: la coda on-premise o il polling di S/4 Public. */
    private String startJobCode;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        if (!AppConfig.getBoolean("EWF_JOBS_ENABLED", true)) {
            System.out.println("[JobHost] Job disattivati su questa istanza (EWF_JOBS_ENABLED=false)");
            return;
        }
        try {
            EwfApp app = EwfApp.get();
            String baseUrl = AppConfig.get("EWF_APP_BASE_URL", "");
            String dest = app.sapDestination().orElse(null);

            JobRegistry registry = new JobRegistry()
                    .register(NotifyJob.KEY, () -> new NotifyJob(app.dataSource(), Clock.system(app.zone()), baseUrl))
                    .register(DigestJob.KEY, () -> new DigestJob(app.dataSource(), Clock.system(app.zone()), baseUrl));
            if (dest != null)
                registry.register(StartQueueJob.KEY, () -> new StartQueueJob(app.engine(), dest));
            S4 s4 = app.s4().orElse(null);
            if (s4 != null) {
                SyncState sync = new SyncState(new Db(app.dataSource()));
                registry.register(PurchaseOrderPollJob.KEY, () -> new PurchaseOrderPollJob(app.engine(), s4.api(),
                        () -> new SyncWatermark(sync, s4.backendCode()), s4.backendCode(), Clock.systemUTC()));
            }
            boolean smtp = AppConfig.get("EONE_SMTP_HOST", null) != null;
            if (smtp) {
                SmtpConfig cfg = SmtpConfig.fromEnv("EONE_SMTP", JobHost::configOrSecret);
                registry.register(MailSendJob.KEY, () -> new MailSendJob(new MailOutbox(new Db(app.dataSource())),
                        new SmtpTransport(cfg), Clock.systemUTC()));
            } else {
                // Senza posta configurata le mail restano in coda: il job non fa nulla (e non riempie lo storico).
                registry.register(MailSendJob.KEY, () -> ctx -> JobResult.idle());
            }

            // Giro dello scheduler ogni 2 secondi (una query leggera): "Aggiorna" non fa aspettare piu' di tanto.
            scheduler = new Scheduler(new Db(app.dataSource()), registry, Scheduler.Options.defaults().withTick(Duration.ofSeconds(2)));
            scheduler.start();   // verifica lo schema eone_connect: se manca o e' vecchio, lo dice e si ferma qui

            if (dest != null)
                scheduler.store().ensure(StartQueueJob.CODE, StartQueueJob.KEY,
                        "Coda degli avvii /EWF/START_Q (" + dest + ")", "every 30s", "Europe/Rome", true, Map.of("batch", "50"));
            if (s4 != null)
                scheduler.store().ensure(PurchaseOrderPollJob.CODE, PurchaseOrderPollJob.KEY,
                        "OdA modificati in S/4 Public (" + s4.backendCode() + ")", "every 1m", "Europe/Rome", true,
                        Map.of("batch", "50"));
            scheduler.store().ensure(NotifyJob.CODE, NotifyJob.KEY, "Avvisi immediati dei task",
                    "every 1m", "Europe/Rome", true, Map.of());
            scheduler.store().ensure(DigestJob.CODE, DigestJob.KEY, "Riepilogo periodico delle attivita'",
                    "daily 07:15 mon-fri", "Europe/Rome", true, Map.of());
            scheduler.store().ensure(MailSendJob.CODE, MailSendJob.KEY, "Spedizione delle mail in coda",
                    "every 1m", "Europe/Rome", true, Map.of("batch", "50"));

            startQueueActive = dest != null || s4 != null;
            startJobCode = s4 != null ? PurchaseOrderPollJob.CODE : StartQueueJob.CODE;
            active = this;
            System.out.println("[JobHost] Job avviati sul nodo " + scheduler.node()
                    + (dest == null ? "" : " - coda degli avvii da " + dest)
                    + (s4 == null ? "" : " - OdA da S/4 Public " + s4.backendCode())
                    + (dest == null && s4 == null ? " - nessun sistema di origine collegato" : "")
                    + (smtp ? "" : " - EONE_SMTP_HOST mancante: le mail restano in coda")
                    + (baseUrl.isBlank() ? " - EWF_APP_BASE_URL mancante: mail senza link" : ""));
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[JobHost] Job NON avviati: " + e.getMessage());
            e.printStackTrace();
            stopScheduler();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        stopScheduler();
        EwfApp.shutdown();
    }

    /** Esito della lettura anticipata della coda degli avvii. */
    public enum QueueRead { NOT_ACTIVE, DONE, FAILED, STILL_RUNNING }

    /**
     * Chiede allo scheduler di leggere subito la coda degli avvii da SAP (non una RFC diretta: cosi' resta
     * un solo lettore anche con piu' nodi e piu' utenti che premono insieme) e attende al massimo maxWait
     * che il giro finisca. Nessuna eccezione: l'esito dice cosa e' successo.
     */
    public static QueueRead readStartQueueNow(Duration maxWait) {
        JobHost h = active;
        if (h == null || h.scheduler == null || !h.startQueueActive) return QueueRead.NOT_ACTIVE;
        try {
            // Fotografia prima della richiesta: il giro e' finito quando cambia l'ultimo "a vuoto" o
            // compare un nuovo giro concluso nello storico (confronto per valori, non per orologi dei nodi).
            Instant idleBefore = lastIdle(h);
            long runBefore = lastRunId(h);
            h.scheduler.store().runNow(h.startJobCode);
            long deadline = System.currentTimeMillis() + maxWait.toMillis();
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(300);
                Instant idle = lastIdle(h);
                if (idle != null && !idle.equals(idleBefore)) return QueueRead.DONE;   // niente di nuovo in coda
                List<JobRun> last = h.scheduler.store().runs(h.startJobCode, 1);
                if (!last.isEmpty() && last.get(0).id() != runBefore && last.get(0).endedAt() != null)
                    return Set.of("ERROR", "FAILED", "ABORTED").contains(last.get(0).status()) ? QueueRead.FAILED : QueueRead.DONE;
            }
            return QueueRead.STILL_RUNNING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QueueRead.STILL_RUNNING;
        } catch (RuntimeException e) {
            System.err.println("[JobHost] Lettura anticipata della coda non riuscita: " + e.getMessage());
            return QueueRead.FAILED;
        }
    }

    private static Instant lastIdle(JobHost h) {
        return h.scheduler.store().definition(h.startJobCode).map(JobDefinition::lastIdleAt).orElse(null);
    }

    private static long lastRunId(JobHost h) {
        List<JobRun> r = h.scheduler.store().runs(h.startJobCode, 1);
        return r.isEmpty() ? -1 : r.get(0).id();
    }

    private void stopScheduler() {
        if (active == this) active = null;
        if (scheduler != null) {
            scheduler.stop();
            scheduler = null;
        }
    }

    /** Valori SMTP: le password solo dall'ambiente, il resto dal file di configurazione o dall'ambiente. */
    private static String configOrSecret(String key) {
        String s = AppConfig.secret(key);
        return s != null ? s : AppConfig.get(key, null);
    }
}
