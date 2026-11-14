package it.eone.connect.mail;

import it.eone.connect.scheduler.Job;
import it.eone.connect.scheduler.JobContext;
import it.eone.connect.scheduler.JobResult;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Job "eone.mail-send": spedisce le mail in coda.
 * Parametri: batch (default 50), keepDays (giorni di conservazione delle mail spedite, default 30).
 * Esito: IDLE se non c'era niente; WARNING se qualche mail e' stata rimandata; ERROR se qualcuna e' DEAD.
 */
public final class MailSendJob implements Job {

    public static final String KEY = "eone.mail-send";
    public static final String CODE = "EONE_MAIL_SEND";

    private final MailOutbox outbox;
    private final MailTransport transport;
    private final Clock clock;

    public MailSendJob(MailOutbox outbox, MailTransport transport, Clock clock) {
        this.outbox = outbox;
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public JobResult run(JobContext ctx) {
        int batch = Integer.parseInt(ctx.param("batch", "50"));
        int keepDays = Integer.parseInt(ctx.param("keepDays", "30"));
        long sent = 0, retry = 0, dead = 0;
        String firstError = null;
        while (!ctx.stopRequested()) {
            List<MailOutbox.Pending> due = outbox.claim(batch, clock.instant());
            for (MailOutbox.Pending p : due) {
                try {
                    transport.send(p.message());
                    outbox.sent(p.id(), clock.instant());
                    sent++;
                } catch (MailException e) {
                    String st = outbox.failed(p.id(), p.attempts(), e, clock.instant());
                    if (st.equals("DEAD")) dead++;
                    else retry++;
                    if (firstError == null) firstError = "mail " + p.id() + ": " + e.getMessage();
                } catch (RuntimeException e) {
                    outbox.failed(p.id(), p.attempts(), new MailException(e.toString(), false, e), clock.instant());
                    retry++;
                    if (firstError == null) firstError = "mail " + p.id() + ": " + e;
                }
            }
            if (due.size() < batch) break;
        }
        long purged = outbox.purgeSent(Duration.ofDays(keepDays), clock.instant());
        if (sent + retry + dead == 0) return JobResult.idle();
        JobResult r = dead > 0 ? JobResult.error(dead + " mail non spedibili (DEAD), " + firstError)
                : retry > 0 ? JobResult.warning(retry + " mail rimandate, " + firstError)
                : JobResult.ok(null);
        return r.with("spedite", sent).with("rimandate", retry).with("scartate", dead).with("cancellate", purged);
    }
}
