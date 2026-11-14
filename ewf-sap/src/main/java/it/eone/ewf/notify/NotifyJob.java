package it.eone.ewf.notify;

import it.eone.connect.db.Db;
import it.eone.connect.mail.MailMessage;
import it.eone.connect.mail.MailOutbox;
import it.eone.connect.scheduler.Job;
import it.eone.connect.scheduler.JobContext;
import it.eone.connect.scheduler.JobResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Job "ewf.notify": trasforma le notifiche del motore (ewf_core.notification) in mail nella coda di uscita.
 *
 * TASK_ACTIVATED -> avviso immediato al destinatario del task:
 *  - task assegnato a una persona: a lei, oppure al suo sostituto se oggi ne ha uno (come il legacy);
 *  - task di team: a tutti i membri del team validi oggi;
 *  - task gia' chiuso (es. saltato in automatico) o destinatario senza indirizzo: nessuna mail, esito registrato.
 * Ripetere e' sicuro: chiave anti-doppione per task e destinatario.
 *
 * Parametri: batch (default 100), baseUrl (indirizzo dell'interfaccia eWF per il link al task; vuoto = nessun link).
 */
public final class NotifyJob implements Job {

    public static final String KEY = "ewf.notify";
    public static final String CODE = "EWF_NOTIFY";

    private final DataSource ds;
    private final MailOutbox outbox;
    private final MailTemplates templates;
    private final Clock clock;

    public NotifyJob(DataSource ds, Clock clock) {
        this.ds = ds;
        this.outbox = new MailOutbox(new Db(ds));
        this.templates = new MailTemplates(ds);
        this.clock = clock;
    }

    private record Pending(long id, String kind, long taskId) {}

    @Override
    public JobResult run(JobContext ctx) throws SQLException {
        int batch = Integer.parseInt(ctx.param("batch", "100"));
        String baseUrl = ctx.param("baseUrl", "");
        LocalDate today = LocalDate.now(clock);
        long mails = 0, skipped = 0;
        while (!ctx.stopRequested()) {
            List<Pending> pending = pending(batch);
            for (Pending n : pending) {
                String result;
                if (!"TASK_ACTIVATED".equals(n.kind())) result = "tipo di notifica sconosciuto: " + n.kind();
                else {
                    Optional<TaskRow> t = task(n.taskId());
                    if (t.isEmpty()) result = "task inesistente";
                    else if (!"OPEN".equals(t.get().status())) result = "task non piu' aperto (" + t.get().status() + ")";
                    else {
                        List<Recipient> to = recipients(t.get(), today);
                        List<String> sent = new ArrayList<>(), missing = new ArrayList<>();
                        for (Recipient r : to) {
                            if (r.email() == null || r.email().isBlank()) { missing.add(r.login()); continue; }
                            outbox.enqueue(message(t.get(), r, baseUrl));
                            sent.add(r.login() + (r.onBehalfOf() != null ? " (per " + r.onBehalfOf() + ")" : ""));
                        }
                        mails += sent.size();
                        result = (sent.isEmpty() ? "nessuna mail" : "mail a " + String.join(", ", sent))
                                + (missing.isEmpty() ? "" : "; senza indirizzo: " + String.join(", ", missing));
                    }
                }
                if (!result.startsWith("mail a")) skipped++;
                done(n.id(), result);
            }
            if (pending.size() < batch) break;
        }
        if (mails + skipped == 0) return JobResult.idle();
        return JobResult.ok(null).with("mail", mails).with("senza mail", skipped);
    }

    private MailMessage message(TaskRow t, Recipient r, String baseUrl) {
        Map<String, String> vars = new HashMap<>(t.vars());
        vars.put("user", r.name());
        if (r.onBehalfOf() != null) vars.put("task", t.taskText() + " (in sostituzione di " + r.onBehalfOf() + ")");
        String url = baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "") + "/task/" + t.taskId();
        vars.put("link", url == null ? "" : "Apri l'attivita': " + url);
        vars.put("linkHtml", url == null ? "" : "<a href=\"" + MailTemplates.escape(url) + "\">Apri l'attivit&agrave;</a>");
        MailTemplates.Rendered m = MailTemplates.render(templates.load("TASK_ACTIVATED", "it"), vars);
        return new MailMessage("ewf", "task " + t.taskId(), "ewf-task-" + t.taskId() + "-" + r.login(),
                List.of(r.email()), List.of(), m.subject(), m.bodyText(), m.bodyHtml());
    }

    // ---------------------------------------------------------------- dati

    private List<Pending> pending(int max) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, kind, task_id FROM ewf_core.notification WHERE processed_at IS NULL ORDER BY id LIMIT ?")) {
            ps.setInt(1, max);
            List<Pending> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Pending(rs.getLong(1), rs.getString(2), rs.getLong(3)));
            }
            return out;
        }
    }

    private Optional<TaskRow> task(long taskId) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + TaskRow.COLUMNS + " FROM " + TaskRow.FROM + " WHERE t.id = ?")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(TaskRow.of(rs, 1, null)) : Optional.empty();
            }
        }
    }

    List<Recipient> recipients(TaskRow t, LocalDate today) throws SQLException {
        try (Connection c = ds.getConnection()) {
            if (t.assigneeId() != null) {
                Recipient owner = user(c, t.assigneeId(), null);
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT substitute_id FROM ewf_org.substitution
                         WHERE user_id = ? AND ? BETWEEN valid_from AND valid_to ORDER BY id DESC LIMIT 1""")) {
                    ps.setLong(1, t.assigneeId());
                    ps.setObject(2, today);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && owner != null) {
                            Recipient sub = user(c, rs.getLong(1), owner.name());
                            if (sub != null) return List.of(sub);
                        }
                    }
                }
                return owner == null ? List.of() : List.of(owner);
            }
            if (t.teamCode() == null) return List.of();
            List<Recipient> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT u.id FROM ewf_org.team_member m JOIN ewf_org.app_user u ON u.id = m.user_id
                     WHERE m.team_code = ? AND u.active
                       AND (m.valid_from IS NULL OR m.valid_from <= ?) AND (m.valid_to IS NULL OR m.valid_to >= ?)
                     ORDER BY u.login""")) {
                ps.setString(1, t.teamCode());
                ps.setObject(2, today);
                ps.setObject(3, today);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Recipient r = user(c, rs.getLong(1), null);
                        if (r != null) out.add(r);
                    }
                }
            }
            return out;
        }
    }

    private static Recipient user(Connection c, long id, String onBehalfOf) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, login, display_name, email FROM ewf_org.app_user WHERE id = ? AND active")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Recipient(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), onBehalfOf)
                        : null;
            }
        }
    }

    private void done(long id, String result) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ewf_core.notification SET processed_at = now(), result = ? WHERE id = ?")) {
            ps.setString(1, result.length() > 200 ? result.substring(0, 200) : result);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }
}
