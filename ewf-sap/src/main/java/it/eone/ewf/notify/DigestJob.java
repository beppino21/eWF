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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Job "ewf.mail-digest": riepilogo periodico delle attivita' in attesa (ex /EWF/WF_SEND_MAIL, ma sui task eWF).
 *
 * Per ogni utente attivo con indirizzo e mail_every_days > 0, se dall'ultimo riepilogo (last_mail_on) sono passati
 * almeno mail_every_days giorni, una sola mail con:
 *  - i task assegnati a lui o presi in carico da lui;
 *  - i task di chi sta sostituendo oggi ("in sostituzione di ...");
 *  - i task di team non ancora presi in carico, dei team di cui fa parte ("da prendere in carico").
 * Chi oggi e' sostituito non riceve il suo riepilogo: i suoi task arrivano al sostituto (come il legacy).
 * Nessuna attivita' in attesa: nessuna mail, e last_mail_on non cambia.
 *
 * Parametri: baseUrl (indirizzo dell'applicazione web eWF; il link e' <baseUrl>/Outest.risc; vuoto = nessun link).
 */
public final class DigestJob implements Job {

    public static final String KEY = "ewf.mail-digest";
    public static final String CODE = "EWF_MAIL_DIGEST";

    private final DataSource ds;
    private final MailOutbox outbox;
    private final MailTemplates templates;
    private final Clock clock;

    private final String defaultBaseUrl;

    public DigestJob(DataSource ds, Clock clock) {
        this(ds, clock, "");
    }

    /**
     * @param defaultBaseUrl indirizzo dell'applicazione web usato quando il job non ha il parametro baseUrl
     *                       (tipicamente EWF_APP_BASE_URL della configurazione dell'applicazione)
     */
    public DigestJob(DataSource ds, Clock clock, String defaultBaseUrl) {
        this.ds = ds;
        this.outbox = new MailOutbox(new Db(ds));
        this.templates = new MailTemplates(ds);
        this.clock = clock;
        this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl;
    }

    private record DigestUser(long id, String login, String name, String email, int everyDays) {}

    @Override
    public JobResult run(JobContext ctx) throws SQLException {
        String baseUrl = ctx.param("baseUrl", "");
        if (baseUrl.isBlank()) baseUrl = defaultBaseUrl;
        LocalDate today = LocalDate.now(clock);
        ZoneId zone = clock.getZone();
        long mails = 0, nothing = 0;
        for (DigestUser u : due(today)) {
            if (ctx.stopRequested()) break;
            List<TaskRow> own = tasks("(t.assignee_id = ? OR t.claimed_by_id = ?)", u.id(), today, null);
            if (substituted(u.id(), today)) own = List.of();          // i suoi task li vede il sostituto
            List<TaskRow> subst = substitutedTasks(u.id(), today);
            List<TaskRow> team = tasks("""
                    t.assignee_id IS NULL AND t.claimed_by_id IS NULL AND t.team_code IN (
                        SELECT m.team_code FROM ewf_org.team_member m WHERE m.user_id = ?
                           AND (m.valid_from IS NULL OR m.valid_from <= ?) AND (m.valid_to IS NULL OR m.valid_to >= ?))""",
                    u.id(), today, null);
            int count = own.size() + subst.size() + team.size();
            if (count == 0) { nothing++; continue; }

            List<TaskRow> mine = new ArrayList<>(own);
            mine.addAll(subst);
            Map<String, String> vars = new HashMap<>();
            vars.put("user", u.name());
            vars.put("count", String.valueOf(count));
            vars.put("everyDays", u.everyDays() == 1 ? "1 giorno" : u.everyDays() + " giorni");
            vars.put("rows", text(mine, today, zone));
            vars.put("rowsHtml", html(mine, today, zone, null));
            vars.put("teamRows", team.isEmpty() ? "" : "Da prendere in carico (team):\n" + text(team, today, zone));
            vars.put("teamRowsHtml", team.isEmpty() ? "" : html(team, today, zone, "Da prendere in carico (team)"));
            String url = baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "") + "/Outest.risc";
            vars.put("link", url == null ? "" : "Apri eWF: " + url);
            vars.put("linkHtml", url == null ? "" : "<a href=\"" + MailTemplates.escape(url) + "\">Apri eWF</a>");
            MailTemplates.Rendered m = MailTemplates.render(templates.load("DIGEST", "it"), vars);
            outbox.enqueue(new MailMessage("ewf", "riepilogo " + u.login(), "ewf-digest-" + u.login() + "-" + today,
                    List.of(u.email()), List.of(), m.subject(), m.bodyText(), m.bodyHtml()));
            markSent(u.id(), today);
            mails++;
        }
        if (mails == 0) return JobResult.idle();
        return JobResult.ok(null).with("riepiloghi", mails).with("senza attivita'", nothing);
    }

    private static String text(List<TaskRow> rows, LocalDate today, ZoneId zone) {
        if (rows.isEmpty()) return "  (nessuna attivita' assegnata a te)\n";
        StringBuilder b = new StringBuilder();
        for (TaskRow r : rows) {
            b.append("- ").append(r.document()).append(" | ").append(r.taskText());
            if (r.forUser() != null) b.append(" (per ").append(r.forUser()).append(")");
            b.append(" | ").append(r.valueText()).append(" | fornitore ").append(r.supplierText())
             .append(" | in attesa da ").append(days(r.waitingDays(today, zone))).append("\n");
        }
        return b.toString();
    }

    private static String html(List<TaskRow> rows, LocalDate today, ZoneId zone, String title) {
        StringBuilder b = new StringBuilder();
        if (title != null) b.append("<p><b>").append(MailTemplates.escape(title)).append("</b></p>");
        if (rows.isEmpty()) return b.append("<p>(nessuna attivit&agrave; assegnata a te)</p>").toString();
        b.append("<table style=\"border-collapse:collapse\" cellpadding=\"4\">")
         .append("<tr style=\"text-align:left;border-bottom:1px solid #ccc\"><th>Documento</th><th>Attivit&agrave;</th>")
         .append("<th style=\"text-align:right\">Valore</th><th>Fornitore</th><th>In attesa da</th></tr>");
        for (TaskRow r : rows) {
            b.append("<tr><td>").append(MailTemplates.escape(r.document())).append("</td><td>")
             .append(MailTemplates.escape(r.taskText()));
            if (r.forUser() != null) b.append(" <i>(per ").append(MailTemplates.escape(r.forUser())).append(")</i>");
            b.append("</td><td style=\"text-align:right\">").append(MailTemplates.escape(r.valueText()))
             .append("</td><td>").append(MailTemplates.escape(r.supplierText())).append("</td><td>")
             .append(days(r.waitingDays(today, zone))).append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    private static String days(long d) {
        return d <= 0 ? "oggi" : d == 1 ? "1 giorno" : d + " giorni";
    }

    // ---------------------------------------------------------------- dati

    private List<DigestUser> due(LocalDate today) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT id, login, display_name, email, mail_every_days FROM ewf_org.app_user
                 WHERE active AND email IS NOT NULL AND email <> '' AND mail_every_days > 0
                   AND (last_mail_on IS NULL OR last_mail_on + mail_every_days <= ?)
                 ORDER BY login""")) {
            ps.setObject(1, today);
            List<DigestUser> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new DigestUser(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
            }
            return out;
        }
    }

    /** Task aperti secondo la condizione; i parametri sono userId, poi (se previsti) data, data. */
    private List<TaskRow> tasks(String where, long userId, LocalDate today, String forUser) throws SQLException {
        String sql = "SELECT " + TaskRow.COLUMNS + " FROM " + TaskRow.FROM
                + " WHERE t.status = 'OPEN' AND p.status = 'RUNNING' AND " + where + " ORDER BY t.created_at, t.id";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int params = (int) where.chars().filter(ch -> ch == '?').count();
            ps.setLong(1, userId);
            if (params == 2) ps.setLong(2, userId);
            if (params == 3) { ps.setObject(2, today); ps.setObject(3, today); }
            List<TaskRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(TaskRow.of(rs, 1, forUser));
            }
            return out;
        }
    }

    private List<TaskRow> substitutedTasks(long substituteId, LocalDate today) throws SQLException {
        List<TaskRow> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT s.user_id, u.display_name FROM ewf_org.substitution s JOIN ewf_org.app_user u ON u.id = s.user_id
                 WHERE s.substitute_id = ? AND ? BETWEEN s.valid_from AND s.valid_to ORDER BY u.login""")) {
            ps.setLong(1, substituteId);
            ps.setObject(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.addAll(tasks("t.assignee_id = ?", rs.getLong(1), today, rs.getString(2)));
            }
        }
        return out;
    }

    private boolean substituted(long userId, LocalDate today) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ewf_org.substitution WHERE user_id = ? AND ? BETWEEN valid_from AND valid_to")) {
            ps.setLong(1, userId);
            ps.setObject(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void markSent(long userId, LocalDate today) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE ewf_org.app_user SET last_mail_on = ? WHERE id = ?")) {
            ps.setObject(1, today);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }
}
