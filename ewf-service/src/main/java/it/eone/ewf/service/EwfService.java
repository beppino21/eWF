package it.eone.ewf.service;

import it.eone.ewf.engine.EngineException;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.Requests.InboxEntry;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.User;
import it.eone.ewf.engine.spi.Contract;
import it.eone.ewf.engine.spi.Handler;
import it.eone.ewf.engine.spi.HandlerContext;
import it.eone.ewf.engine.spi.HandlerRegistry;
import it.eone.ewf.engine.spi.HandlerResult;
import it.eone.ewf.engine.spi.UserDirectory;
import it.eone.ewf.service.ServiceException.Code;
import it.eone.ewf.service.Views.*;
import it.eone.ewf.service.spi.DocRef;
import it.eone.ewf.service.spi.DocumentAttachments;
import it.eone.ewf.service.spi.DocumentSheets;
import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.sheet.SheetPdf;
import it.eone.ewf.service.spi.SapGuiLink;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * Livello di servizio dell'applicazione transazionale: lista, dettaglio, azioni, commenti, allegati, PDF,
 * collegamento SAP GUI, funzioni speciali. Ogni metodo riceve l'utente collegato (userId della Session) e
 * controlla da se' cosa quell'utente puo' vedere e fare: l'interfaccia non deve fidarsi di se stessa.
 *
 * Chi vede un processo: chi l'ha avviato, chi ha (o ha avuto) un task su quel processo, i membri dei team dei suoi
 * task, e chi sostituisce oggi una di queste persone. Chi agisce: lo decide il motore (titolare, sostituto, team).
 */
public final class EwfService {

    /** Limiti dell'applicazione. */
    /** adminTeam: i membri attivi di questo team (amministratori eWF) vedono tutti i processi in consultazione. */
    public record Config(long maxAttachmentBytes, int maxCommentLength, String adminTeam) {
        public Config(long maxAttachmentBytes, int maxCommentLength) { this(maxAttachmentBytes, maxCommentLength, "WFADM"); }
        public static Config defaults() { return new Config(20L * 1024 * 1024, 4000); }
    }

    /** Integrazioni col sistema di origine: ciascuna puo' mancare (null), la funzione allora non compare. */
    public record Backends(DocumentAttachments attachments, DocumentSheets sheets, SapGuiLink sapGui) {
        public static Backends none() { return new Backends(null, null, null); }
    }

    private final WorkflowEngine engine;
    private final JdbcTransactions db;
    private final UserDirectory users;
    private final HandlerRegistry functions;
    private final Backends backends;
    private final Clock clock;
    private final ZoneId zone;
    private final Config config;

    public EwfService(WorkflowEngine engine, JdbcTransactions db, UserDirectory users, HandlerRegistry functions,
                      Backends backends, Clock clock, ZoneId zone, Config config) {
        this.engine = engine;
        this.db = db;
        this.users = users;
        this.functions = functions;
        this.backends = backends == null ? Backends.none() : backends;
        this.clock = clock;
        this.zone = zone;
        this.config = config == null ? Config.defaults() : config;
    }

    // =====================================================================
    // Lista delle attivita'
    // =====================================================================

    public Worklist worklist(long userId, WorklistFilter filter) {
        Map<Long, InboxEntry> inbox = new LinkedHashMap<>();
        for (InboxEntry e : engine(() -> engine.inbox(userId))) inbox.put(e.taskId(), e);
        if (inbox.isEmpty()) return new Worklist(List.of(), List.of());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        List<WorkItem> all = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id, t.process_id, t.created_at, t.team_code, td.text AS task_text, td.interaction,
                           p.process_type_code, pt.description AS pt_text, p.doc_type, p.document_key,
                           p.text AS p_text, p.net_value, p.currency, su.display_name AS starter,
                           au.display_name AS assignee, pt.list_summary, pt.sla_yellow_days, pt.sla_red_days,
                           ARRAY(SELECT e.key || chr(31) || e.value FROM jsonb_each_text(p.doc_context) e) AS ctx
                      FROM ewf_core.task_instance t
                      JOIN ewf_core.task_def td ON td.id = t.task_def_id
                      JOIN ewf_core.process_instance p ON p.id = t.process_id
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                      LEFT JOIN ewf_org.app_user su ON su.id = p.started_by
                      LEFT JOIN ewf_org.app_user au ON au.id = t.assignee_id
                     WHERE t.id = ANY(?)
                     ORDER BY t.created_at, t.id""")) {
                ps.setArray(1, c.createArrayOf("bigint", inbox.keySet().toArray()));
                List<WorkItem> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        InboxEntry e = inbox.get(rs.getLong("id"));
                        Reason reason = Reason.valueOf(e.reason().name());
                        String forWhom = switch (reason) {
                            case OWN -> null;
                            case SUBSTITUTE -> rs.getString("assignee");
                            case TEAM -> rs.getString("team_code");
                        };
                        Money value = money(rs);
                        Map<String, String> attrs = attributes(rs, value);
                        Instant since = rs.getTimestamp("created_at").toInstant();
                        int days = (int) Math.max(0, ChronoUnit.DAYS.between(since.atZone(zone).toLocalDate(), today));
                        String summary = SummaryTemplate.render(rs.getString("list_summary"), attrs);
                        if (summary == null) summary = rs.getString("p_text");
                        out.add(new WorkItem(e.taskId(), e.processId(), rs.getString("process_type_code"),
                                rs.getString("pt_text"), rs.getString("doc_type"), rs.getString("document_key"),
                                rs.getString("task_text"), reason, forWhom, rs.getString("starter"), since, days,
                                light(days, rs.getInt("sla_yellow_days"), rs.getInt("sla_red_days")), value,
                                summary, attrs, "WEB".equals(rs.getString("interaction"))));
                    }
                }
                return out;
            }
        });

        Map<String, TypeCount> counts = new LinkedHashMap<>();
        for (WorkItem w : all)
            counts.merge(w.processType(), new TypeCount(w.processType(), w.processTypeText(), 1),
                    (a, b) -> new TypeCount(a.processType(), a.text(), a.count() + 1));
        WorklistFilter f = filter == null ? WorklistFilter.all() : filter;
        String text = f.text() == null || f.text().isBlank() ? null : f.text().trim().toLowerCase(Locale.ROOT);
        List<WorkItem> items = all.stream()
                .filter(w -> f.processType() == null || f.processType().equals(w.processType()))
                .filter(w -> text == null || matches(w, text))
                .toList();
        List<TypeCount> tc = new ArrayList<>(counts.values());
        tc.sort(Comparator.comparing(TypeCount::text, Comparator.nullsLast(String::compareToIgnoreCase)));
        return new Worklist(items, tc);
    }

    private static boolean matches(WorkItem w, String text) {
        for (String s : new String[]{w.documentKey(), w.summary(), w.taskText(), w.starter(), w.processTypeText(), w.forWhom()})
            if (s != null && s.toLowerCase(Locale.ROOT).contains(text)) return true;
        return false;
    }

    static Light light(int days, int yellowFrom, int redFrom) {
        return days >= redFrom ? Light.RED : days >= yellowFrom ? Light.YELLOW : Light.GREEN;
    }

    /** Colonne specifiche del tipo di processo, per la lista filtrata. */
    public List<Column> columns(String processType) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT x.col ->> 'label', x.col ->> 'key'
                      FROM ewf_core.process_type pt
                     CROSS JOIN LATERAL jsonb_array_elements(pt.list_columns) WITH ORDINALITY AS x(col, n)
                     WHERE pt.code = ? ORDER BY x.n""")) {
                ps.setString(1, processType);
                List<Column> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) if (rs.getString(2) != null)
                        out.add(new Column(rs.getString(1) == null ? rs.getString(2) : rs.getString(1), rs.getString(2)));
                }
                return out;
            }
        });
    }

    // =====================================================================
    // Dettaglio
    // =====================================================================

    public ProcessDetail detail(long userId, long processId) {
        requireView(userId, processId);
        Header h = header(processId);
        List<Requests.PathEntry> path = engine(() -> engine.path(processId));
        MyTask my = myTask(userId, processId);
        DocRef doc = docRef(processId);
        String sapUser = users.byId(userId).map(User::sapUser).orElse(null);
        Links links = new Links(sheetViews(doc),
                sapUser != null && !sapUser.isBlank() && backends.sapGui() != null && backends.sapGui().supports(doc),
                "DOCUMENT".equals(h.attachmentScope()) && backends.attachments() != null && backends.attachments().supports(doc));
        return new ProcessDetail(h, path, history(processId), comments(userId, processId), my, links);
    }

    private Header header(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, p.process_type_code, pt.description AS pt_text, p.doc_type, p.document_key,
                           p.text AS p_text, p.status, p.status_reason, p.net_value, p.currency,
                           su.display_name AS starter, p.started_at, p.ended_at, pt.attachment_scope,
                           ARRAY(SELECT e.key || chr(31) || e.value FROM jsonb_each_text(p.doc_context) e) AS ctx
                      FROM ewf_core.process_instance p
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                      LEFT JOIN ewf_org.app_user su ON su.id = p.started_by
                     WHERE p.id = ?""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ServiceException.of(Code.NOT_FOUND, "Processo %d inesistente", processId);
                    Money value = money(rs);
                    return new Header(rs.getLong("id"), rs.getString("process_type_code"), rs.getString("pt_text"),
                            rs.getString("doc_type"), rs.getString("document_key"), rs.getString("p_text"),
                            rs.getString("status"), rs.getString("status_reason"), value, rs.getString("starter"),
                            instant(rs, "started_at"), instant(rs, "ended_at"), rs.getString("attachment_scope"),
                            attributes(rs, value));
                }
            }
        });
    }

    /**
     * Linea del tempo del processo per il Gantt: l'avvio e tutti i task, anche quelli ancora aperti,
     * in ordine di arrivo. Stesse regole di visibilita' del dettaglio.
     */
    /**
     * Contatori per i pulsanti Allegati / Commenti / Storico della lista: una sola query per tutta la pagina.
     * Considera solo i processi che l'utente puo' vedere (gli altri restano a zero).
     */
    public Map<Long, InfoCounts> infoCounts(long userId, Collection<Long> processIds) {
        Map<Long, InfoCounts> out = new HashMap<>();
        if (processIds == null || processIds.isEmpty()) return out;
        List<Long> visible = processIds.stream().distinct().filter(p -> canView(userId, p)).toList();
        if (visible.isEmpty()) return out;
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, pt.attachment_scope,
                           (SELECT count(*) FROM ewf_core.attachment a
                             WHERE a.process_id = p.id AND a.provider = 'LOCAL' AND a.deleted_at IS NULL),
                           (SELECT count(*) FROM ewf_core.comment pc WHERE pc.process_id = p.id),
                           (SELECT count(*) FROM ewf_core.task_instance t
                             WHERE t.process_id = p.id AND t.status NOT IN ('OPEN', 'EXECUTING'))
                      FROM ewf_core.process_instance p
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                     WHERE p.id = ANY (?)""")) {
                ps.setArray(1, c.createArrayOf("bigint", visible.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean document = "DOCUMENT".equals(rs.getString(2));
                        out.put(rs.getLong(1), new InfoCounts(document ? -1 : rs.getInt(3), rs.getInt(4), rs.getInt(5)));
                    }
                }
                return out;
            }
        });
    }

    /**
     * Processi a cui l'utente ha partecipato: avviati da lui, task su cui ha agito (anche come sostituto),
     * commenti o allegati suoi. Piu' recenti prima, al massimo 500.
     */
    public List<ParticipatedProcess> participated(long userId, ParticipationFilter filter) {
        String status = switch (filter == null ? ParticipationFilter.ALL : filter) {
            case RUNNING -> " AND p.status IN ('RUNNING','SUSPENDED','COMPLETING','ERROR')";
            case CLOSED -> " AND p.status NOT IN ('RUNNING','SUSPENDED','COMPLETING','ERROR')";
            case ALL -> "";
        };
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT * FROM (
                      SELECT p.id, p.process_type_code, pt.description AS pt_text, p.doc_type, p.document_key,
                             p.text AS p_text, p.net_value, p.currency, p.status, p.started_at, p.ended_at,
                             su.display_name AS starter, pt.list_summary,
                             ARRAY(SELECT e.key || chr(31) || e.value FROM jsonb_each_text(p.doc_context) e) AS ctx,
                             (p.started_by = ?) AS i_started,
                             EXISTS (SELECT 1 FROM ewf_core.task_instance t WHERE t.process_id = p.id AND t.acted_by_id = ? AND t.status = 'OK') AS i_ok,
                             EXISTS (SELECT 1 FROM ewf_core.task_instance t WHERE t.process_id = p.id AND t.acted_by_id = ? AND t.status = 'KO') AS i_ko,
                             EXISTS (SELECT 1 FROM ewf_core.task_instance t WHERE t.process_id = p.id AND t.acted_by_id = ? AND t.status NOT IN ('OK','KO')) AS i_other,
                             EXISTS (SELECT 1 FROM ewf_core.comment pc WHERE pc.process_id = p.id AND pc.author_id = ?) AS i_comment,
                             EXISTS (SELECT 1 FROM ewf_core.attachment pa WHERE pa.process_id = p.id AND pa.uploaded_by = ?) AS i_attach,
                             (SELECT string_agg(td.text, ', ' ORDER BY t.step_no) FROM ewf_core.task_instance t
                                JOIN ewf_core.task_def td ON td.id = t.task_def_id
                               WHERE t.process_id = p.id AND t.status IN ('OPEN','EXECUTING')) AS open_tasks
                        FROM ewf_core.process_instance p
                        JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                        LEFT JOIN ewf_org.app_user su ON su.id = p.started_by
                       WHERE 1 = 1""" + status + """
                    ) x
                    WHERE i_started OR i_ok OR i_ko OR i_other OR i_comment OR i_attach
                    ORDER BY started_at DESC, id DESC
                    LIMIT 500""")) {
                for (int i = 1; i <= 6; i++) ps.setLong(i, userId);
                List<ParticipatedProcess> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Money value = money(rs);
                        Map<String, String> attrs = attributes(rs, value);
                        String summary = SummaryTemplate.render(rs.getString("list_summary"), attrs);
                        if (summary == null) summary = rs.getString("p_text");
                        List<String> roles = new ArrayList<>();
                        if (rs.getBoolean("i_started")) roles.add("avviato");
                        if (rs.getBoolean("i_ok")) roles.add("approvato");
                        if (rs.getBoolean("i_ko")) roles.add("respinto");
                        if (rs.getBoolean("i_other")) roles.add("gestito");
                        if (rs.getBoolean("i_comment")) roles.add("commentato");
                        if (rs.getBoolean("i_attach")) roles.add("allegato");
                        out.add(new ParticipatedProcess(rs.getLong("id"), rs.getString("process_type_code"),
                                rs.getString("pt_text"), rs.getString("doc_type"), rs.getString("document_key"),
                                summary, value, rs.getString("status"), instant(rs, "started_at"), instant(rs, "ended_at"),
                                rs.getString("starter"), String.join(", ", roles), rs.getString("open_tasks")));
                    }
                }
                return out;
            }
        });
    }

    public List<TimelineBar> timeline(long userId, long processId) {
        requireView(userId, processId);
        Header h = header(processId);
        List<TimelineBar> out = new ArrayList<>();
        out.add(new TimelineBar(h.starter() == null ? "Avvio" : h.starter(), "Avvio del processo", "START", null,
                h.startedAt(), h.startedAt(), null, null));
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT td.text, t.status, t.option_code, o.label AS option_label, t.note, t.created_at, t.closed_at,
                           t.acted_as_subst, t.team_code,
                           ab.display_name AS acted_by, asg.display_name AS assignee
                      FROM ewf_core.task_instance t
                      JOIN ewf_core.task_def td ON td.id = t.task_def_id
                      LEFT JOIN ewf_core.task_option o ON o.task_def_id = t.task_def_id AND o.option_code = t.option_code
                      LEFT JOIN ewf_org.app_user ab ON ab.id = t.acted_by_id
                      LEFT JOIN ewf_org.app_user asg ON asg.id = t.assignee_id
                     WHERE t.process_id = ?
                     ORDER BY t.created_at, t.id""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String who = rs.getString("acted_by");
                        if (who == null) who = rs.getString("assignee");
                        if (who == null && rs.getString("team_code") != null) who = "Team " + rs.getString("team_code");
                        if (who == null) who = "(non assegnato)";
                        String opt = rs.getString("option_label") != null ? rs.getString("option_label") : rs.getString("option_code");
                        out.add(new TimelineBar(who, rs.getString("text"), rs.getString("status"), opt,
                                instant(rs, "created_at"), instant(rs, "closed_at"),
                                rs.getBoolean("acted_as_subst") ? rs.getString("assignee") : null, rs.getString("note")));
                    }
                }
            }
            return null;
        });
        return out;
    }

    private List<HistoryEntry> history(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id, t.step_no, td.text, t.status, t.option_code, o.label AS option_label,
                           ab.display_name AS acted_by, t.acted_as_subst, asg.display_name AS assignee,
                           mv.display_name AS moved_to, t.note, t.created_at, t.closed_at
                      FROM ewf_core.task_instance t
                      JOIN ewf_core.task_def td ON td.id = t.task_def_id
                      LEFT JOIN ewf_core.task_option o ON o.task_def_id = t.task_def_id AND o.option_code = t.option_code
                      LEFT JOIN ewf_org.app_user ab ON ab.id = t.acted_by_id
                      LEFT JOIN ewf_org.app_user asg ON asg.id = t.assignee_id
                      LEFT JOIN ewf_org.app_user mv ON mv.id = t.moved_to_id
                     WHERE t.process_id = ? AND t.status NOT IN ('OPEN', 'EXECUTING')
                     ORDER BY coalesce(t.closed_at, t.created_at), t.id""")) {
                ps.setLong(1, processId);
                List<HistoryEntry> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String opt = rs.getString("option_label") != null ? rs.getString("option_label") : rs.getString("option_code");
                        out.add(new HistoryEntry(rs.getLong("id"), rs.getInt("step_no"), rs.getString("text"),
                                rs.getString("status"), opt, rs.getString("acted_by"),
                                rs.getBoolean("acted_as_subst") ? rs.getString("assignee") : null,
                                rs.getString("moved_to"), rs.getString("note"),
                                instant(rs, "created_at"), instant(rs, "closed_at")));
                    }
                }
                return out;
            }
        });
    }

    private MyTask myTask(long userId, long processId) {
        Optional<InboxEntry> e = engine(() -> engine.inbox(userId)).stream()
                .filter(x -> x.processId() == processId)
                .min(Comparator.comparing((InboxEntry x) -> x.reason().ordinal()).thenComparingLong(InboxEntry::taskId));
        if (e.isEmpty()) return null;
        long taskId = e.get().taskId();
        List<Option> options = engine(() -> engine.options(taskId)).stream()
                .map(o -> new Option(o.code(), o.label(), o.reject())).toList();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT td.text, td.interaction, t.row_version, t.team_code, au.display_name AS assignee
                      FROM ewf_core.task_instance t
                      JOIN ewf_core.task_def td ON td.id = t.task_def_id
                      LEFT JOIN ewf_org.app_user au ON au.id = t.assignee_id
                     WHERE t.id = ?""")) {
                ps.setLong(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Reason reason = Reason.valueOf(e.get().reason().name());
                    String forWhom = reason == Reason.SUBSTITUTE ? rs.getString("assignee")
                            : reason == Reason.TEAM ? rs.getString("team_code") : null;
                    return new MyTask(taskId, rs.getString("text"), reason, forWhom,
                            "WEB".equals(rs.getString("interaction")), options, taskFunctions(c, taskId),
                            rs.getInt("row_version"));
                }
            }
        });
    }

    private static List<Function> taskFunctions(Connection c, long taskId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT f.code, f.label, f.confirm_text
                  FROM ewf_core.task_function f
                  JOIN ewf_core.task_instance t ON t.task_def_id = f.task_def_id
                 WHERE t.id = ? ORDER BY f.position, f.code""")) {
            ps.setLong(1, taskId);
            List<Function> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Function(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
            return out;
        }
    }

    // =====================================================================
    // Azioni sul task (le autorizzazioni le verifica il motore)
    // =====================================================================

    public Requests.Outcome approve(long userId, long taskId, String note, Integer rowVersion) {
        requireWeb(taskId);
        return engine(() -> engine.act(new Requests.Act(taskId, userId, Requests.Action.OK, blankToNull(note), rowVersion)));
    }

    /** Il rifiuto richiede sempre una motivazione: chi riceve il documento indietro deve sapere perche'. */
    public Requests.Outcome reject(long userId, long taskId, String note, Integer rowVersion) {
        if (blankToNull(note) == null)
            throw new ServiceException(Code.INVALID, "Indicare il motivo del rifiuto");
        requireWeb(taskId);
        return engine(() -> engine.act(new Requests.Act(taskId, userId, Requests.Action.KO, note.trim(), rowVersion)));
    }

    /** Nodo di scelta: l'opzione al posto di Approva/Respingi. Un'opzione di rifiuto vuole la motivazione. */
    public Requests.Outcome choose(long userId, long taskId, String optionCode, String note, Integer rowVersion) {
        boolean reject = engine(() -> engine.options(taskId)).stream()
                .anyMatch(o -> o.code().equals(optionCode) && o.reject());
        if (reject && blankToNull(note) == null)
            throw new ServiceException(Code.INVALID, "Indicare il motivo del rifiuto");
        requireWeb(taskId);
        return engine(() -> engine.choose(new Requests.Choose(taskId, userId, optionCode, blankToNull(note), rowVersion)));
    }

    /** Sposta il task a un altro utente; restituisce l'id del nuovo task. */
    public long move(long userId, long taskId, long toUserId, String note) {
        requireWeb(taskId);
        return engine(() -> engine.move(taskId, userId, toUserId, blankToNull(note)));
    }

    /** Utenti attivi a cui si puo' spostare un task (esclusi quelli nascosti nelle liste). */
    public List<UserChoice> findUsers(String text, int limit) {
        String q = text == null ? "" : text.trim();
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, login, display_name FROM ewf_org.app_user
                     WHERE active AND NOT hidden_in_lists
                       AND (? = '' OR login ILIKE ? OR display_name ILIKE ?)
                     ORDER BY display_name LIMIT ?""")) {
                String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                ps.setString(1, q);
                ps.setString(2, like);
                ps.setString(3, like);
                ps.setInt(4, Math.max(1, Math.min(limit, 200)));
                List<UserChoice> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new UserChoice(rs.getLong(1), rs.getString(2), rs.getString(3)));
                }
                return out;
            }
        });
    }

    private void requireWeb(long taskId) {
        String interaction = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT td.interaction FROM ewf_core.task_instance t
                      JOIN ewf_core.task_def td ON td.id = t.task_def_id WHERE t.id = ?""")) {
                ps.setLong(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        if (interaction == null) throw ServiceException.of(Code.NOT_FOUND, "Task %d inesistente", taskId);
        if (!"WEB".equals(interaction))
            throw new ServiceException(Code.NOT_SUPPORTED, "Questa attivita' si completa in SAP GUI");
    }

    // =====================================================================
    // Commenti
    // =====================================================================

    public Comment addComment(long userId, long processId, String text) {
        String t = blankToNull(text);
        if (t == null) throw new ServiceException(Code.INVALID, "Il commento e' vuoto");
        if (t.length() > config.maxCommentLength())
            throw ServiceException.of(Code.INVALID, "Il commento supera i %d caratteri", config.maxCommentLength());
        requireView(userId, processId);
        MyTask my = myTask(userId, processId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.comment (process_id, task_id, kind, author_id, body, created_at)
                    VALUES (?, ?, 'N', ?, ?, ?) RETURNING id, created_at""")) {
                ps.setLong(1, processId);
                if (my == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, my.taskId());
                ps.setLong(3, userId);
                ps.setString(4, t);
                ps.setTimestamp(5, Timestamp.from(clock.instant()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Comment(rs.getLong(1), nameOf(userId), t, instant(rs, "created_at"), true);
                }
            }
        });
    }

    private List<Comment> comments(long userId, long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT pc.id, u.display_name, pc.body, pc.created_at, pc.author_id
                      FROM ewf_core.comment pc
                      LEFT JOIN ewf_org.app_user u ON u.id = pc.author_id
                     WHERE pc.process_id = ? ORDER BY pc.id""")) {
                ps.setLong(1, processId);
                List<Comment> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Comment(rs.getLong(1), rs.getString(2), rs.getString(3),
                            instant(rs, "created_at"), rs.getLong(5) == userId));
                }
                return out;
            }
        });
    }

    // =====================================================================
    // Allegati: in eWF (PROCESS) o nel sistema di origine (DOCUMENT), secondo il tipo di processo
    // =====================================================================

    public List<Attachment> attachments(long userId, long processId) {
        requireView(userId, processId);
        Scope s = scope(processId);
        if (s.document()) {
            DocumentAttachments da = documentAttachments(s.doc());
            List<DocumentAttachments.Item> items = backend(() -> da.list(s.doc()));
            return items.stream().map(i -> new Attachment("D:" + i.id(), i.fileName(), i.mimeType(), i.sizeBytes(),
                    i.createdBy(), i.createdAt(), false)).toList();
        }
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT a.id, a.filename, a.mime_type, a.size_bytes, u.display_name, a.uploaded_at, a.uploaded_by
                      FROM ewf_core.attachment a
                      LEFT JOIN ewf_org.app_user u ON u.id = a.uploaded_by
                     WHERE a.process_id = ? AND a.provider = 'LOCAL' AND a.deleted_at IS NULL ORDER BY a.id""")) {
                ps.setLong(1, processId);
                List<Attachment> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Attachment("P:" + rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getLong(4), rs.getString(5), instant(rs, "uploaded_at"),
                            s.running() && rs.getLong(7) == userId));
                }
                return out;
            }
        });
    }

    public FileContent attachmentContent(long userId, long processId, String attachmentId) {
        requireView(userId, processId);
        Scope s = scope(processId);
        if (attachmentId != null && attachmentId.startsWith("D:") && s.document()) {
            DocumentAttachments da = documentAttachments(s.doc());
            String id = attachmentId.substring(2);
            DocumentAttachments.Item item = backend(() -> da.list(s.doc())).stream()
                    .filter(i -> i.id().equals(id)).findFirst()
                    .orElseThrow(() -> new ServiceException(Code.NOT_FOUND, "Allegato non trovato"));
            byte[] content = backend(() -> da.read(s.doc(), id));
            return new FileContent(item.fileName(), mimeOrDefault(item.mimeType()), content);
        }
        long id = processAttachmentId(attachmentId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT a.filename, a.mime_type, ac.content
                      FROM ewf_core.attachment a
                      JOIN ewf_core.attachment_content ac ON ac.attachment_id = a.id
                     WHERE a.id = ? AND a.process_id = ? AND a.provider = 'LOCAL' AND a.deleted_at IS NULL""")) {
                ps.setLong(1, id);
                ps.setLong(2, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Allegato non trovato");
                    return new FileContent(rs.getString(1), rs.getString(2), rs.getBytes(3));
                }
            }
        });
    }

    public Attachment addAttachment(long userId, long processId, String fileName, String mimeType, byte[] content) {
        if (content == null || content.length == 0) throw new ServiceException(Code.INVALID, "Il file e' vuoto");
        if (content.length > config.maxAttachmentBytes())
            throw ServiceException.of(Code.INVALID, "Il file supera il limite di %d MB", config.maxAttachmentBytes() / (1024 * 1024));
        String name = safeFileName(fileName);
        String mime = mimeOrDefault(mimeType);
        requireView(userId, processId);
        Scope s = scope(processId);
        if (s.document()) {
            DocumentAttachments da = documentAttachments(s.doc());
            Optional<User> u = users.byId(userId);
            String id = backend(() -> da.add(s.doc(), name, mime, content,
                    u.map(User::sapUser).orElse(null), u.map(User::displayName).orElse(null)));
            return new Attachment("D:" + id, name, mime, (long) content.length, nameOf(userId), clock.instant(), false);
        }
        if (!s.running()) throw new ServiceException(Code.INVALID_STATE, "Il processo e' concluso: allegati non modificabili");
        String sha = sha256(content);
        MyTask my = myTask(userId, processId);
        return db.inTransaction(() -> db.with(c -> {
            long id;
            Instant at;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.attachment (process_id, task_id, provider, filename, mime_type, size_bytes,
                                                     sha256, uploaded_by, uploaded_at)
                    VALUES (?, ?, 'LOCAL', ?, ?, ?, ?, ?, ?) RETURNING id, uploaded_at""")) {
                ps.setLong(1, processId);
                if (my == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, my.taskId());
                ps.setString(3, name);
                ps.setString(4, mime);
                ps.setLong(5, content.length);
                ps.setString(6, sha);
                ps.setLong(7, userId);
                ps.setTimestamp(8, Timestamp.from(clock.instant()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                    at = instant(rs, "uploaded_at");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ewf_core.attachment_content (attachment_id, content) VALUES (?, ?)")) {
                ps.setLong(1, id);
                ps.setBytes(2, content);
                ps.executeUpdate();
            }
            return new Attachment("P:" + id, name, mime, (long) content.length, nameOf(userId), at, true);
        }));
    }

    /** Solo allegati di processo, solo chi li ha caricati, solo a processo aperto. Restano nel DB, nascosti. */
    public void deleteAttachment(long userId, long processId, String attachmentId) {
        requireView(userId, processId);
        long id = processAttachmentId(attachmentId);
        if (!scope(processId).running())
            throw new ServiceException(Code.INVALID_STATE, "Il processo e' concluso: allegati non modificabili");
        int n = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.attachment SET deleted_by = ?, deleted_at = ?
                     WHERE id = ? AND process_id = ? AND uploaded_by = ? AND deleted_at IS NULL""")) {
                ps.setLong(1, userId);
                ps.setTimestamp(2, Timestamp.from(clock.instant()));
                ps.setLong(3, id);
                ps.setLong(4, processId);
                ps.setLong(5, userId);
                return ps.executeUpdate();
            }
        });
        if (n == 0) throw new ServiceException(Code.NOT_AUTHORIZED, "Si possono eliminare solo i propri allegati");
    }

    private record Scope(boolean document, boolean running, DocRef doc) {}

    private Scope scope(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT pt.attachment_scope, p.status, p.backend_code, p.doc_type, p.document_key
                      FROM ewf_core.process_instance p
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code WHERE p.id = ?""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ServiceException.of(Code.NOT_FOUND, "Processo %d inesistente", processId);
                    String st = rs.getString(2);
                    return new Scope("DOCUMENT".equals(rs.getString(1)), "RUNNING".equals(st) || "SUSPENDED".equals(st),
                            new DocRef(rs.getString(3), rs.getString(4), rs.getString(5)));
                }
            }
        });
    }

    private DocumentAttachments documentAttachments(DocRef doc) {
        DocumentAttachments da = backends.attachments();
        if (da == null || !da.supports(doc))
            throw ServiceException.of(Code.NOT_SUPPORTED, "Allegati del documento non disponibili per %s", doc.docType());
        return da;
    }

    private static long processAttachmentId(String attachmentId) {
        if (attachmentId == null || !attachmentId.startsWith("P:"))
            throw new ServiceException(Code.NOT_FOUND, "Allegato non trovato");
        try {
            return Long.parseLong(attachmentId.substring(2));
        } catch (NumberFormatException e) {
            throw new ServiceException(Code.NOT_FOUND, "Allegato non trovato");
        }
    }

    /** Solo il nome (niente percorsi), senza caratteri di controllo, al massimo 255 caratteri. */
    static String safeFileName(String fileName) {
        String n = fileName == null ? "" : fileName;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("[\\p{Cntrl}\"<>|:*?]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isBlank()) n = "allegato";
        if (n.length() > 255) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 255 - ext.length()) + ext;
        }
        return n;
    }

    static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mimeOrDefault(String mime) {
        return mime == null || mime.isBlank() || mime.length() > 120 ? "application/octet-stream" : mime.trim();
    }

    // =====================================================================
    // Stampa PDF e collegamento SAP GUI
    // =====================================================================

    /**
     * Scheda PDF del documento: la vista chiesta (dal sistema di origine, o "EWF" = riepilogo senza sistema di
     * origine) con in coda la parte eWF (approvazioni, prossimi passi, commenti) come la vede l'utente ora.
     */
    public FileContent documentSheet(long userId, long processId, String viewCode) {
        ProcessDetail d = detail(userId, processId);                 // anche controllo di visibilita'
        DocRef doc = docRef(processId);
        DocumentSheet sheet;
        String code = viewCode == null ? SheetSections.EWF_VIEW : viewCode;
        if (SheetSections.EWF_VIEW.equals(code)) {
            sheet = SheetSections.ewfView(d, columns(d.header().processType()), zone);
        } else {
            DocumentSheets ds = backends.sheets();
            if (ds == null || ds.views(doc).stream().noneMatch(v -> v.code().equals(code)))
                throw ServiceException.of(Code.NOT_SUPPORTED, "Scheda %s non disponibile per %s", code, doc.docType());
            sheet = backend(() -> ds.build(doc, code, d.header().attributes())).with(SheetSections.approval(d, zone));
        }
        byte[] pdf = new SheetPdf(zone).render(sheet, clock.instant());
        String name = (doc.docType() + "_" + doc.documentKey() + "_" + code).replaceAll("[^A-Za-z0-9_.-]", "_") + ".pdf";
        return new FileContent(name, "application/pdf", pdf);
    }

    private List<SheetView> sheetViews(DocRef doc) {
        List<SheetView> out = new ArrayList<>();
        if (backends.sheets() != null)
            for (DocumentSheets.View v : backends.sheets().views(doc)) out.add(new SheetView(v.code(), v.label()));
        out.add(new SheetView(SheetSections.EWF_VIEW, SheetSections.EWF_VIEW_LABEL));
        return out;
    }

    public FileContent sapGuiShortcut(long userId, long processId) {
        requireView(userId, processId);
        String sapUser = users.byId(userId).map(User::sapUser).orElse(null);
        if (sapUser == null || sapUser.isBlank())
            throw new ServiceException(Code.NOT_SUPPORTED, "Nessun utente SAP associato: chiedere all'amministratore");
        DocRef doc = docRef(processId);
        SapGuiLink link = backends.sapGui();
        if (link == null || !link.supports(doc))
            throw ServiceException.of(Code.NOT_SUPPORTED, "Visualizzazione in SAP non disponibile per %s", doc.docType());
        SapGuiLink.Shortcut s = backend(() -> link.shortcut(doc, sapUser));
        return new FileContent(s.fileName(), s.mimeType(), s.content());
    }

    private DocRef docRef(long processId) {
        return scope(processId).doc();
    }

    // =====================================================================
    // Funzioni speciali del task
    // =====================================================================

    /**
     * Esegue una funzione speciale del task (pulsante aggiuntivo nel dettaglio) con il suo handler, contratto
     * TASK_ACTION e azione = codice funzione. Non cambia lo stato del processo: l'esito torna all'utente.
     */
    public FunctionResult runFunction(long userId, long taskId, String code) {
        InboxEntry e = engine(() -> engine.inbox(userId)).stream().filter(x -> x.taskId() == taskId).findFirst()
                .orElseThrow(() -> new ServiceException(Code.NOT_AUTHORIZED, "Attivita' non disponibile per l'utente"));
        record Fn(String handler, String processType, String docType, String documentKey, String backend,
                  UUID correlation, Money value, int stepNo, int subopNo, String role, String taskParam) {}
        Fn fn = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT f.handler_code, p.process_type_code, p.doc_type, p.document_key, p.backend_code,
                           p.correlation_key, p.net_value, p.currency, t.step_no, t.subop_no, t.role_code, td.task_param
                      FROM ewf_core.task_instance t
                      JOIN ewf_core.task_def td ON td.id = t.task_def_id
                      JOIN ewf_core.task_function f ON f.task_def_id = t.task_def_id AND f.code = ?
                      JOIN ewf_core.process_instance p ON p.id = t.process_id
                     WHERE t.id = ?""")) {
                ps.setString(1, code);
                ps.setLong(2, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ServiceException.of(Code.NOT_FOUND, "Funzione %s non prevista per questa attivita'", code);
                    return new Fn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            (UUID) rs.getObject(6), money(rs), rs.getInt(9), rs.getInt(10), rs.getString(11), rs.getString(12));
                }
            }
        });
        Handler h = functions.find(fn.handler())
                .orElseThrow(() -> ServiceException.of(Code.NOT_SUPPORTED, "Funzione %s non disponibile (handler %s)", code, fn.handler()));
        User u = users.byId(userId).orElseThrow(() -> new ServiceException(Code.NOT_FOUND, "Utente non trovato"));
        HandlerContext ctx = new HandlerContext(UUID.randomUUID(), Contract.TASK_ACTION, null, code,
                new HandlerContext.ProcessRef(e.processId(), fn.processType(), fn.docType(), fn.documentKey(),
                        fn.backend(), fn.correlation(), fn.value()),
                new HandlerContext.TaskRef(taskId, fn.stepNo(), fn.subopNo(), fn.role(), fn.taskParam()),
                new HandlerContext.Actor(userId, u.login(), u.sapUser(), e.reason() == InboxEntry.Reason.SUBSTITUTE, false),
                Map.of("FUNCTION", code));
        HandlerResult r;
        try {
            r = h.execute(ctx);
        } catch (RuntimeException ex) {
            throw new ServiceException(Code.BACKEND, "Funzione non eseguita: " + ex.getMessage(), ex);
        }
        return switch (r.status()) {
            case OK -> new FunctionResult(true, r.message() == null ? "Eseguito" : r.message());
            case KO -> throw new ServiceException(Code.FUNCTION_FAILED, r.message() == null ? "Funzione non riuscita" : r.message());
            case UNKNOWN -> new FunctionResult(false, "Esito incerto, verificare nel sistema di origine"
                    + (r.message() == null ? "" : ": " + r.message()));
        };
    }

    // =====================================================================
    // Visibilita' e utilita'
    // =====================================================================

    public boolean canView(long userId, long processId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Set<Long> ids = new HashSet<>(users.substitutedBy(userId, today));
        ids.add(userId);
        Set<String> teams = users.teamsOf(userId, today);
        if (config.adminTeam() != null && teams.contains(config.adminTeam())) return processExists(processId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM ewf_core.process_instance p
                         WHERE p.id = ? AND (p.started_by = ANY(?) OR EXISTS (
                               SELECT 1 FROM ewf_core.task_instance t
                                WHERE t.process_id = p.id
                                  AND (t.assignee_id = ANY(?) OR t.acted_by_id = ANY(?) OR t.claimed_by_id = ANY(?)
                                       OR t.from_user_id = ANY(?) OR t.moved_to_id = ANY(?) OR t.team_code = ANY(?)))
                               OR EXISTS (SELECT 1 FROM ewf_core.comment pc WHERE pc.process_id = p.id AND pc.author_id = ?)
                               OR EXISTS (SELECT 1 FROM ewf_core.attachment pa WHERE pa.process_id = p.id AND pa.uploaded_by = ?)))""")) {
                Array a = c.createArrayOf("bigint", ids.toArray());
                ps.setLong(1, processId);
                for (int i = 2; i <= 7; i++) ps.setArray(i, a);
                ps.setArray(8, c.createArrayOf("varchar", teams.toArray()));
                ps.setLong(9, userId);                       // chi ha commentato o allegato continua a vedere il processo
                ps.setLong(10, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
    }

    private boolean processExists(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM ewf_core.process_instance WHERE id = ?")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private void requireView(long userId, long processId) {
        if (!canView(userId, processId)) {
            header(processId);                                           // NOT_FOUND se non esiste
            throw new ServiceException(Code.NOT_AUTHORIZED, "Processo non visibile per l'utente");
        }
    }

    private String nameOf(long userId) {
        return users.byId(userId).map(User::displayName).orElse("#" + userId);
    }

    private static <T> T engine(Supplier<T> call) {
        try {
            return call.get();
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }

    private static <T> T backend(Supplier<T> call) {
        try {
            return call.get();
        } catch (ServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ServiceException(Code.BACKEND, "Sistema di origine non disponibile: " + e.getMessage(), e);
        }
    }

    private static Money money(ResultSet rs) throws SQLException {
        BigDecimal v = rs.getBigDecimal("net_value");
        String cur = rs.getString("currency");
        return v == null || cur == null ? null : new Money(v, cur);
    }

    /** Attributi del documento piu' quelli calcolati, per riepilogo e colonne. */
    private static Map<String, String> attributes(ResultSet rs, Money value) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        Array arr = rs.getArray("ctx");
        if (arr != null) for (Object o : (Object[]) arr.getArray()) {
            String kv = (String) o;
            int i = kv.indexOf('\u001f');
            if (i > 0) out.put(kv.substring(0, i), kv.substring(i + 1));
        }
        out.put("documentKey", rs.getString("document_key"));
        out.put("processType", rs.getString("process_type_code"));
        putIfNotNull(out, "processText", rs.getString("p_text"));
        putIfNotNull(out, "starter", rs.getString("starter"));
        if (value != null) {
            out.put("value", formatAmount(value.amount()));
            out.put("currency", value.currency());
        }
        return out;
    }

    private static void putIfNotNull(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    public static String formatAmount(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ITALY));
        return f.format(v);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
