package it.eone.ewf.service;

import it.eone.ewf.engine.jdbc.JdbcDefinitionStore;
import it.eone.ewf.engine.jdbc.JdbcDefinitionWriter;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.service.ServiceException.Code;

import it.eone.ewf.engine.jdbc.JdbcException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Designer dei processi (funzione da amministratore). Primo passo: consultazione di tipi processo e versioni,
 * con la definizione caricata dal motore e i problemi della validazione. La modifica delle bozze arrivera' qui.
 * <p>
 * Regola delle versioni: si modificano solo le bozze (DRAFT); l'attivazione valida e ritira la versione attiva;
 * i processi in corso restano sulla versione con cui sono partiti.
 */
public final class DesignerService {

    /** Un tipo processo con lo stato delle sue versioni. */
    public record TypeRow(String code, String description, String docType, boolean suspended, boolean manualStart,
                          Integer activeVersionNo, int drafts, int versions, long running) {}

    /** Una versione. running = processi in corso su questa versione. */
    public record VersionRow(long id, String processType, int versionNo, String status, Instant createdAt, String createdBy,
                             Instant activatedAt, String note, int steps, long running) {}

    /** Un problema della validazione (severity ERROR blocca l'attivazione, WARNING no). */
    public record Problem(String severity, Integer stepNo, String text) {
        public boolean isError() { return "ERROR".equals(severity); }
    }

    /**
     * Una versione aperta nel designer. definition e' null se la definizione non si riesce nemmeno a caricare
     * (bozza rotta): in quel caso restano i problemi.
     */
    public record VersionView(VersionRow row, String typeDescription, ProcessVersion definition, List<Problem> problems) {
        public boolean hasErrors() { return problems.stream().anyMatch(Problem::isError); }
    }

    private final JdbcTransactions db;
    private final AdminService admin;

    public DesignerService(JdbcTransactions db, AdminService admin) {
        this.db = db;
        this.admin = admin;
    }

    private void requireAdmin(long userId) {
        if (!admin.isAdmin(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Funzione riservata agli amministratori");
    }

    /** Tutti i tipi processo, in ordine di codice. */
    public List<TypeRow> types(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.code, t.description, t.doc_type, t.suspended, t.manual_start,
                           (SELECT v.version_no FROM ewf_core.process_version v
                             WHERE v.process_type_code = t.code AND v.status = 'ACTIVE') AS active_no,
                           (SELECT count(*) FROM ewf_core.process_version v
                             WHERE v.process_type_code = t.code AND v.status = 'DRAFT') AS drafts,
                           (SELECT count(*) FROM ewf_core.process_version v WHERE v.process_type_code = t.code) AS versions,
                           (SELECT count(*) FROM ewf_core.process_instance p
                             WHERE p.process_type_code = t.code AND p.status IN ('RUNNING','SUSPENDED','ERROR')) AS running
                      FROM ewf_core.process_type t
                     ORDER BY t.code""")) {
                List<TypeRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int a = rs.getInt("active_no");
                        out.add(new TypeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                                rs.getBoolean(5), rs.wasNull() || a == 0 ? null : a, rs.getInt("drafts"),
                                rs.getInt("versions"), rs.getLong("running")));
                    }
                }
                return out;
            }
        });
    }

    /** Le versioni di un tipo processo, dalla piu' recente. */
    public List<VersionRow> versions(long adminId, String processType) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(VERSION_SQL + " WHERE v.process_type_code = ? ORDER BY v.version_no DESC")) {
                ps.setString(1, processType);
                List<VersionRow> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(versionRow(rs));
                }
                return out;
            }
        });
    }

    /** Una versione con la definizione e la validazione. */
    public VersionView view(long adminId, long versionId) {
        requireAdmin(adminId);
        VersionRow row = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(VERSION_SQL + " WHERE v.id = ?")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Versione inesistente");
                    return versionRow(rs);
                }
            }
        });
        String typeDescription = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT description FROM ewf_core.process_type WHERE code = ?")) {
                ps.setString(1, row.processType());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : row.processType();
                }
            }
        });
        List<Problem> problems = new ArrayList<>();
        for (JdbcDefinitionWriter.Problem p : new JdbcDefinitionWriter(db).validate(versionId))
            problems.add(new Problem(p.severity(), p.stepNo(), p.problem()));
        ProcessVersion def = null;
        try {
            def = new JdbcDefinitionStore(db).load(versionId);
        } catch (RuntimeException e) {
            if (problems.stream().noneMatch(Problem::isError))
                problems.add(new Problem("ERROR", null, "Definizione non leggibile: " + e.getMessage()));
        }
        problems.sort((a, b) -> {
            int s = Boolean.compare(!a.isError(), !b.isError());
            if (s != 0) return s;
            int x = a.stepNo() == null ? -1 : a.stepNo(), y = b.stepNo() == null ? -1 : b.stepNo();
            return Integer.compare(x, y);
        });
        return new VersionView(row, typeDescription, def, problems);
    }

    private static final String VERSION_SQL = """
            SELECT v.id, v.process_type_code, v.version_no, v.status, v.created_at, u.display_name, v.activated_at, v.note,
                   (SELECT count(*) FROM ewf_core.step_def s WHERE s.version_id = v.id) AS steps,
                   (SELECT count(*) FROM ewf_core.process_instance p
                     WHERE p.version_id = v.id AND p.status IN ('RUNNING','SUSPENDED','ERROR')) AS running
              FROM ewf_core.process_version v
              LEFT JOIN ewf_org.app_user u ON u.id = v.created_by""";

    private static VersionRow versionRow(ResultSet rs) throws java.sql.SQLException {
        return new VersionRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), instant(rs.getTimestamp(5)),
                rs.getString(6), instant(rs.getTimestamp(7)), rs.getString(8), rs.getInt("steps"), rs.getLong("running"));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    // ================================================================== modifica delle bozze

    /** Uno step della bozza, come sta nel database (anche se la versione non e' valida). */
    public record StepRow(long id, int stepNo, String option, boolean first, String join, Integer nextOk, Integer nextKo,
                          BigDecimal minAmount, String currency, List<TaskRow> tasks) {}

    /** Un task della bozza. hooks/options/functions: quanti agganci, opzioni e funzioni speciali ha. */
    public record TaskRow(long id, long stepId, int subopNo, String text, String assigneeMode, String roleCode,
                          String handlerCode, boolean teamTask, String teamCode, int requiredWeight, String previewMode,
                          String interaction, String notifyMode, String taskParam, int hooks, int options, int functions,
                          String commentOnApprove, String commentOnReject) {}

    /** Dati di uno step da salvare; stepId null = nuovo. nextOk/nextKo null = fine. */
    public record StepEdit(Long stepId, int stepNo, String option, boolean first, String join, Integer nextOk,
                           Integer nextKo, BigDecimal minAmount, String currency) {}

    /** Dati di un task da salvare; taskId null = nuovo (nello step stepId). */
    public record TaskEdit(Long taskId, long stepId, String text, String assigneeMode, String roleCode, String handlerCode,
                           boolean teamTask, String teamCode, int requiredWeight, String previewMode, String interaction,
                           String notifyMode, String taskParam, String commentOnApprove, String commentOnReject) {}

    /** Azione di fine processo e nota della versione. */
    public record VersionEdit(String endHandlerCode, String endHandlerConfig, String note) {}

    /** Voce per le liste di scelta (ruoli, team, handler, tipi processo). */
    public record Choice(String code, String text) {}

    /** Step e task di una versione, letti direttamente dal database. */
    public List<StepRow> steps(long adminId, long versionId) {
        requireAdmin(adminId);
        return db.with(c -> stepRows(c, versionId));
    }

    /** Copia completa di una versione (qualsiasi stato) in una nuova bozza dello stesso tipo processo. */
    public long newVersion(long adminId, long fromVersionId) {
        requireAdmin(adminId);
        return tx(() -> db.with(c -> {
            long newId;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.process_version(process_type_code, version_no, status, end_handler_id,
                                                         end_handler_config, created_by, note)
                    SELECT v.process_type_code,
                           (SELECT max(x.version_no) + 1 FROM ewf_core.process_version x
                             WHERE x.process_type_code = v.process_type_code),
                           'DRAFT', v.end_handler_id, v.end_handler_config, ?, 'Copia della versione ' || v.version_no
                      FROM ewf_core.process_version v WHERE v.id = ?
                    RETURNING id""")) {
                ps.setLong(1, adminId);
                ps.setLong(2, fromVersionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Versione inesistente");
                    newId = rs.getLong(1);
                }
            }
            // step -> task -> agganci, opzioni, funzioni: tutte le colonne, anche quelle che il modello non usa
            for (long oldStep : ids(c, "SELECT id FROM ewf_core.step_def WHERE version_id = ? ORDER BY id", fromVersionId)) {
                long newStep = copyRow(c, "step_def", oldStep, "version_id", newId);
                for (long oldTask : ids(c, "SELECT id FROM ewf_core.task_def WHERE step_id = ? ORDER BY id", oldStep)) {
                    long newTask = copyRow(c, "task_def", oldTask, "step_id", newStep);
                    copyChildren(c, "task_hook", oldTask, newTask);
                    copyChildren(c, "task_option", oldTask, newTask);
                    copyChildren(c, "task_function", oldTask, newTask);
                }
            }
            return newId;
        }));
    }

    /** Elimina una bozza con tutto il suo contenuto. */
    public void deleteDraft(long adminId, long versionId) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            for (String t : List.of("task_hook", "task_option", "task_function"))
                update(c, "DELETE FROM ewf_core." + t + " WHERE task_def_id IN (SELECT d.id FROM ewf_core.task_def d"
                        + " JOIN ewf_core.step_def s ON s.id = d.step_id WHERE s.version_id = ?)", versionId);
            update(c, "DELETE FROM ewf_core.task_def WHERE step_id IN (SELECT id FROM ewf_core.step_def WHERE version_id = ?)", versionId);
            update(c, "DELETE FROM ewf_core.step_def WHERE version_id = ?", versionId);
            update(c, "DELETE FROM ewf_core.process_version WHERE id = ?", versionId);
            return null;
        }));
    }

    /** Azione finale (handler di tipo PROCESS_END e suoi parametri in JSON) e nota. */
    public void saveVersion(long adminId, long versionId, VersionEdit e) {
        requireAdmin(adminId);
        String cfg = e.endHandlerConfig() == null || e.endHandlerConfig().isBlank() ? "{}" : e.endHandlerConfig().trim();
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            Long handler = blank(e.endHandlerCode()) ? null : handlerId(c, e.endHandlerCode(), "PROCESS_END");
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.process_version SET end_handler_id = ?, end_handler_config = ?::jsonb, note = ?
                     WHERE id = ?""")) {
                setLong(ps, 1, handler);
                ps.setString(2, cfg);
                ps.setString(3, trimToNull(e.note()));
                ps.setLong(4, versionId);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    /**
     * Salva uno step. Se diventa il primo, gli altri smettono di esserlo. Se cambia numero e non resta nessuno
     * step con il numero vecchio, le frecce (approvato / respinto) che puntavano al vecchio numero seguono.
     * @return id dello step
     */
    public long saveStep(long adminId, long versionId, StepEdit e) {
        requireAdmin(adminId);
        if (e.stepNo() <= 0) throw new ServiceException(Code.INVALID, "Il numero dello step deve essere positivo");
        String join = "ANY".equals(e.join()) ? "ANY" : "ALL";
        String option = trimToNull(e.option());
        if (e.minAmount() != null && e.minAmount().signum() <= 0)
            throw new ServiceException(Code.INVALID, "La soglia deve essere positiva (vuota = sempre)");
        String currency = e.minAmount() == null ? null : blank(e.currency()) ? "EUR" : e.currency().trim().toUpperCase();
        return tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            long id;
            Integer oldNo = null;
            if (e.stepId() == null) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO ewf_core.step_def(version_id, step_no, is_first, join_policy, next_step_ok, next_step_ko,
                                                      min_amount, min_amount_currency, option_code)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                    ps.setLong(1, versionId);
                    fillStep(ps, 2, e, join, currency, option);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
            } else {
                id = e.stepId();
                oldNo = stepNoOf(c, versionId, id);
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE ewf_core.step_def SET step_no = ?, is_first = ?, join_policy = ?, next_step_ok = ?,
                               next_step_ko = ?, min_amount = ?, min_amount_currency = ?, option_code = ?
                         WHERE id = ? AND version_id = ?""")) {
                    fillStep(ps, 1, e, join, currency, option);
                    ps.setLong(9, id);
                    ps.setLong(10, versionId);
                    ps.executeUpdate();
                }
            }
            if (e.first())
                update(c, "UPDATE ewf_core.step_def SET is_first = false WHERE version_id = ? AND id <> ? AND is_first",
                        versionId, id);
            if (oldNo != null && oldNo != e.stepNo()
                    && ids(c, "SELECT id FROM ewf_core.step_def WHERE version_id = ? AND step_no = " + oldNo, versionId).isEmpty()) {
                update(c, "UPDATE ewf_core.step_def SET next_step_ok = " + e.stepNo() + " WHERE version_id = ? AND next_step_ok = " + oldNo, versionId);
                update(c, "UPDATE ewf_core.step_def SET next_step_ko = " + e.stepNo() + " WHERE version_id = ? AND next_step_ko = " + oldNo, versionId);
            }
            return id;
        }));
    }

    /** Elimina uno step con i suoi task. Le frecce che vi puntavano restano: la validazione le segnala. */
    public void deleteStep(long adminId, long versionId, long stepId) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            stepNoOf(c, versionId, stepId);
            for (String t : List.of("task_hook", "task_option", "task_function"))
                update(c, "DELETE FROM ewf_core." + t + " WHERE task_def_id IN (SELECT id FROM ewf_core.task_def WHERE step_id = ?)", stepId);
            update(c, "DELETE FROM ewf_core.task_def WHERE step_id = ?", stepId);
            update(c, "DELETE FROM ewf_core.step_def WHERE id = ?", stepId);
            return null;
        }));
    }

    /** Salva un task (nuovo: in fondo allo step). @return id del task */
    public long saveTask(long adminId, long versionId, TaskEdit e) {
        requireAdmin(adminId);
        if (blank(e.text())) throw new ServiceException(Code.INVALID, "Il testo del task e' obbligatorio");
        String mode = e.assigneeMode() == null ? "RULES" : e.assigneeMode();
        if (!List.of("RULES", "HANDLER", "STARTER", "ACTOR", "PREVIOUS").contains(mode))
            throw new ServiceException(Code.INVALID, "Esecutore non valido: " + mode);
        String role = trimToNull(e.roleCode()), team = trimToNull(e.teamCode()), handler = trimToNull(e.handlerCode());
        if ("RULES".equals(mode) && role == null && team == null)
            throw new ServiceException(Code.INVALID, "Con le regole serve un ruolo (o un team fisso)");
        if ("HANDLER".equals(mode) && handler == null)
            throw new ServiceException(Code.INVALID, "Indicare l'handler che sceglie l'esecutore");
        if (e.requiredWeight() < 0) throw new ServiceException(Code.INVALID, "Il peso non puo' essere negativo");
        if (e.text().trim().length() > 80) throw new ServiceException(Code.INVALID, "Testo troppo lungo (massimo 80 caratteri)");
        return tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            stepNoOf(c, versionId, e.stepId());
            Long handlerId = "HANDLER".equals(mode) ? handlerId(c, handler, "ASSIGNEE") : null;
            if (e.taskId() == null) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO ewf_core.task_def(step_id, subop_no, text, role_code, required_weight, assignee_mode,
                               assignee_handler_id, team_task, team_code, preview_mode, interaction, notify, task_param,
                               comment_on_approve, comment_on_reject)
                        VALUES (?, (SELECT coalesce(max(subop_no), -1) + 1 FROM ewf_core.task_def WHERE step_id = ?),
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                    ps.setLong(1, e.stepId());
                    ps.setLong(2, e.stepId());
                    fillTask(ps, 3, e, mode, role, team, handlerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.task_def SET text = ?, role_code = ?, required_weight = ?, assignee_mode = ?,
                           assignee_handler_id = ?, team_task = ?, team_code = ?, preview_mode = ?, interaction = ?,
                           notify = ?, task_param = ?, comment_on_approve = ?, comment_on_reject = ?
                     WHERE id = ? AND step_id = ?""")) {
                fillTask(ps, 1, e, mode, role, team, handlerId);
                ps.setLong(14, e.taskId());
                ps.setLong(15, e.stepId());
                if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Task non trovato nello step");
            }
            return e.taskId();
        }));
    }

    /** Elimina un task con agganci, opzioni e funzioni. */
    public void deleteTask(long adminId, long versionId, long taskId) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            for (String t : List.of("task_hook", "task_option", "task_function"))
                update(c, "DELETE FROM ewf_core." + t + " WHERE task_def_id = ?", taskId);
            update(c, "DELETE FROM ewf_core.task_def WHERE id = ?", taskId);
            return null;
        }));
    }

    /**
     * Attiva una bozza: la validazione non deve trovare errori; la versione attiva viene ritirata.
     * I processi in corso restano sulla loro versione.
     */
    public void activate(long adminId, long versionId) {
        requireAdmin(adminId);
        db.with(c -> {
            requireDraft(c, versionId);
            return null;
        });
        try {
            new JdbcDefinitionWriter(db).activate(versionId);
        } catch (JdbcException e) {
            throw new ServiceException(Code.INVALID, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ liste di scelta

    public List<Choice> roles(long adminId) {
        requireAdmin(adminId);
        return choices("SELECT code, description FROM ewf_org.role ORDER BY code");
    }

    public List<Choice> teams(long adminId) {
        requireAdmin(adminId);
        return choices("SELECT code, description FROM ewf_org.team ORDER BY code");
    }

    /** Handler di un contratto: ASSIGNEE (scelta dell'esecutore), PROCESS_END (azione finale), TASK_ACTION. */
    public List<Choice> handlers(long adminId, String contract) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code, coalesce(description, target) FROM ewf_core.handler WHERE contract = ? ORDER BY code")) {
                ps.setString(1, contract);
                return readChoices(ps);
            }
        });
    }

    /** Codici delle opzioni dei nodi di scelta della versione (per le varianti di step). */
    public List<Choice> optionCodes(long adminId, long versionId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT DISTINCT o.option_code, o.label FROM ewf_core.task_option o
                      JOIN ewf_core.task_def d ON d.id = o.task_def_id
                      JOIN ewf_core.step_def s ON s.id = d.step_id
                     WHERE s.version_id = ? AND NOT o.reject AND o.spawn_mode IS DISTINCT FROM 'HANDOFF'
                     ORDER BY o.option_code""")) {
                ps.setLong(1, versionId);
                return readChoices(ps);
            }
        });
    }

    /** Azione finale e nota attuali della versione. */
    public VersionEdit versionSettings(long adminId, long versionId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT h.code, v.end_handler_config::text, v.note FROM ewf_core.process_version v
                      LEFT JOIN ewf_core.handler h ON h.id = v.end_handler_id WHERE v.id = ?""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Versione inesistente");
                    String cfg = rs.getString(2);
                    return new VersionEdit(rs.getString(1), "{}".equals(cfg) ? "" : cfg, rs.getString(3));
                }
            }
        });
    }

    // ------------------------------------------------------------------ interni

    private List<StepRow> stepRows(Connection c, long versionId) throws SQLException {
        Map<Long, List<TaskRow>> tasks = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT d.id, d.step_id, d.subop_no, d.text, d.assignee_mode, d.role_code, h.code, d.team_task, d.team_code,
                       d.required_weight, d.preview_mode, d.interaction, d.notify, d.task_param,
                       (SELECT count(*) FROM ewf_core.task_hook x WHERE x.task_def_id = d.id),
                       (SELECT count(*) FROM ewf_core.task_option x WHERE x.task_def_id = d.id),
                       (SELECT count(*) FROM ewf_core.task_function x WHERE x.task_def_id = d.id),
                       d.comment_on_approve, d.comment_on_reject
                  FROM ewf_core.task_def d
                  JOIN ewf_core.step_def s ON s.id = d.step_id
                  LEFT JOIN ewf_core.handler h ON h.id = d.assignee_handler_id
                 WHERE s.version_id = ? ORDER BY d.step_id, d.subop_no""")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    tasks.computeIfAbsent(rs.getLong(2), k -> new ArrayList<>()).add(new TaskRow(rs.getLong(1), rs.getLong(2),
                            rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getBoolean(8), rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12),
                            rs.getString(13), rs.getString(14), rs.getInt(15), rs.getInt(16), rs.getInt(17),
                            rs.getString(18), rs.getString(19)));
            }
        }
        List<StepRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, step_no, option_code, is_first, join_policy, next_step_ok, next_step_ko, min_amount,
                       min_amount_currency
                  FROM ewf_core.step_def WHERE version_id = ? ORDER BY step_no, coalesce(option_code, '')""")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    out.add(new StepRow(id, rs.getInt(2), rs.getString(3), rs.getBoolean(4), rs.getString(5),
                            intOrNull(rs, 6), intOrNull(rs, 7), rs.getBigDecimal(8), rs.getString(9),
                            tasks.getOrDefault(id, List.of())));
                }
            }
        }
        return out;
    }

    private void fillStep(PreparedStatement ps, int i, StepEdit e, String join, String currency, String option)
            throws SQLException {
        ps.setInt(i, e.stepNo());
        ps.setBoolean(i + 1, e.first());
        ps.setString(i + 2, join);
        setInt(ps, i + 3, e.nextOk());
        setInt(ps, i + 4, e.nextKo());
        if (e.minAmount() == null) ps.setNull(i + 5, Types.NUMERIC); else ps.setBigDecimal(i + 5, e.minAmount());
        ps.setString(i + 6, currency);
        ps.setString(i + 7, option);
    }

    private void fillTask(PreparedStatement ps, int i, TaskEdit e, String mode, String role, String team, Long handlerId)
            throws SQLException {
        ps.setString(i, e.text().trim());
        ps.setString(i + 1, "RULES".equals(mode) ? role : null);
        ps.setInt(i + 2, e.requiredWeight());
        ps.setString(i + 3, mode);
        setLong(ps, i + 4, handlerId);
        ps.setBoolean(i + 5, e.teamTask());
        ps.setString(i + 6, team);
        ps.setString(i + 7, oneOf(e.previewMode(), "NAME", "NAME", "ROLE", "HIDDEN"));
        ps.setString(i + 8, oneOf(e.interaction(), "WEB", "WEB", "INTERACTIVE", "SAPGUI_ONLY"));
        ps.setString(i + 9, oneOf(e.notifyMode(), "NONE", "NONE", "IMMEDIATE"));
        ps.setString(i + 10, trimToNull(e.taskParam()));
        ps.setString(i + 11, oneOf(e.commentOnApprove(), "OPTIONAL", "NONE", "OPTIONAL", "REQUIRED"));
        ps.setString(i + 12, oneOf(e.commentOnReject(), "REQUIRED", "NONE", "OPTIONAL", "REQUIRED"));
    }

    private void requireDraft(Connection c, long versionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM ewf_core.process_version WHERE id = ?")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Versione inesistente");
                if (!"DRAFT".equals(rs.getString(1)))
                    throw new ServiceException(Code.INVALID_STATE,
                            "Si modificano solo le bozze: per cambiare questa versione creane una nuova");
            }
        }
    }

    private int stepNoOf(Connection c, long versionId, long stepId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT step_no FROM ewf_core.step_def WHERE id = ? AND version_id = ?")) {
            ps.setLong(1, stepId);
            ps.setLong(2, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Step non trovato nella versione");
                return rs.getInt(1);
            }
        }
    }

    private void requireTaskInVersion(Connection c, long versionId, long taskId) throws SQLException {
        if (ids(c, "SELECT d.id FROM ewf_core.task_def d JOIN ewf_core.step_def s ON s.id = d.step_id"
                + " WHERE s.version_id = ? AND d.id = " + taskId, versionId).isEmpty())
            throw new ServiceException(Code.NOT_FOUND, "Task non trovato nella versione");
    }

    private Long handlerId(Connection c, String code, String contract) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, contract FROM ewf_core.handler WHERE code = ?")) {
            ps.setString(1, code.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ServiceException(Code.INVALID, "Handler " + code + " inesistente");
                if (!contract.equals(rs.getString(2)))
                    throw new ServiceException(Code.INVALID, "Handler " + code + " non adatto (" + rs.getString(2)
                            + ", serve " + contract + ")");
                return rs.getLong(1);
            }
        }
    }

    /** Copia una riga cambiandone il genitore; tutte le colonne tranne id. @return id della copia */
    private long copyRow(Connection c, String table, long id, String parentCol, long newParent) throws SQLException {
        List<String> cols = columns(c, table);
        cols.remove("id");
        cols.remove(parentCol);
        String list = String.join(", ", cols);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ewf_core." + table + "(" + parentCol + ", " + list
                + ") SELECT ?, " + list + " FROM ewf_core." + table + " WHERE id = ? RETURNING id")) {
            ps.setLong(1, newParent);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Copia le righe figlie di un task (agganci, opzioni, funzioni) sul task nuovo. */
    private void copyChildren(Connection c, String table, long oldTask, long newTask) throws SQLException {
        List<String> cols = columns(c, table);
        cols.remove("id");
        cols.remove("task_def_id");
        String list = String.join(", ", cols);
        update(c, "INSERT INTO ewf_core." + table + "(task_def_id, " + list + ") SELECT " + newTask + ", " + list
                + " FROM ewf_core." + table + " WHERE task_def_id = ?", oldTask);
    }

    private final Map<String, List<String>> columnCache = new java.util.concurrent.ConcurrentHashMap<>();

    private List<String> columns(Connection c, String table) throws SQLException {
        List<String> cached = columnCache.get(table);
        if (cached != null) return new ArrayList<>(cached);
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'ewf_core' AND table_name = ? ORDER BY ordinal_position""")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        columnCache.put(table, List.copyOf(out));
        return out;
    }

    private static List<Long> ids(Connection c, String sql, long param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            List<Long> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
            return out;
        }
    }

    private static int update(Connection c, String sql, long... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setLong(i + 1, params[i]);
            return ps.executeUpdate();
        }
    }

    private List<Choice> choices(String sql) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                return readChoices(ps);
            }
        });
    }

    private static List<Choice> readChoices(PreparedStatement ps) throws SQLException {
        List<Choice> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Choice(rs.getString(1), rs.getString(2)));
        }
        return out;
    }

    /** Transazione; gli errori del database diventano messaggi comprensibili. */
    private <T> T tx(Supplier<T> work) {
        try {
            return db.inTransaction(work);
        } catch (JdbcException e) {
            SQLException s = e.getCause() instanceof SQLException x ? x : null;
            String state = s == null ? "" : s.getSQLState();
            String msg = switch (state == null ? "" : state) {
                case "23505" -> "Esiste gia' un elemento con questo numero o codice (es. step con lo stesso numero e ramo)";
                case "23503" -> "Riferimento inesistente (ruolo, team o handler)";
                case "22P02" -> "Parametri non validi: serve un oggetto JSON, es. {\"Z_WF_SET_RIL\":\"01\"}";
                case "23514" -> s.getMessage().contains("non in bozza") || s.getMessage().contains("ritirare")
                        ? "Si modificano solo le bozze" : "Valore non ammesso: " + firstLine(s.getMessage());
                default -> "Errore del database: " + firstLine(e.getMessage());
            };
            throw new ServiceException(Code.INVALID, msg);
        }
    }

    private static String firstLine(String m) {
        if (m == null) return "";
        int i = m.indexOf('\n');
        return i < 0 ? m : m.substring(0, i);
    }

    private static Integer intOrNull(ResultSet rs, int i) throws SQLException {
        int v = rs.getInt(i);
        return rs.wasNull() ? null : v;
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }

    private static String oneOf(String v, String dflt, String... allowed) {
        if (v == null) return dflt;
        for (String a : allowed) if (a.equals(v)) return v;
        throw new ServiceException(Code.INVALID, "Valore non ammesso: " + v);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return blank(s) ? null : s.trim();
    }

    // ================================================================== agganci, opzioni, funzioni speciali

    /** Aggancio di un task: evento -> handler. config in JSON ("" = nessun parametro). */
    public record HookRow(String event, int seq, String handlerCode, String label, String config) {}

    /** Opzione di un nodo di scelta. startProcess + spawnMode: processo da avviare sullo stesso documento. */
    public record OptionRow(String code, int seq, String label, boolean reject, String handlerCode, String config,
                            String startProcess, String spawnMode) {}

    /** Funzione speciale: pulsante aggiuntivo nel dettaglio, eseguito da un handler. */
    public record FunctionRow(long id, String code, String label, String handlerCode, String confirmText, int position) {}

    /** Agganci, opzioni e funzioni di un task. */
    public record TaskExtras(List<HookRow> hooks, List<OptionRow> options, List<FunctionRow> functions) {}

    public TaskExtras extras(long adminId, long versionId, long taskId) {
        requireAdmin(adminId);
        return db.with(c -> {
            requireTaskInVersion(c, versionId, taskId);
            List<HookRow> hooks = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT k.event, k.seq, h.code, k.label, k.config::text FROM ewf_core.task_hook k
                      JOIN ewf_core.handler h ON h.id = k.handler_id
                     WHERE k.task_def_id = ? ORDER BY CASE k.event WHEN 'ON_ACTIVATE' THEN 1 WHEN 'BEFORE_ACTION' THEN 2
                           WHEN 'ON_OK' THEN 3 WHEN 'ON_KO' THEN 4 ELSE 5 END, k.seq""")) {
                ps.setLong(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) hooks.add(new HookRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            emptyJson(rs.getString(5))));
                }
            }
            List<OptionRow> options = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT o.option_code, o.seq, o.label, o.reject, h.code, o.config::text, o.start_process_type, o.spawn_mode
                      FROM ewf_core.task_option o LEFT JOIN ewf_core.handler h ON h.id = o.handler_id
                     WHERE o.task_def_id = ? ORDER BY o.seq""")) {
                ps.setLong(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) options.add(new OptionRow(rs.getString(1), rs.getInt(2), rs.getString(3),
                            rs.getBoolean(4), rs.getString(5), emptyJson(rs.getString(6)), rs.getString(7), rs.getString(8)));
                }
            }
            List<FunctionRow> functions = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, code, label, handler_code, confirm_text, position FROM ewf_core.task_function
                     WHERE task_def_id = ? ORDER BY position, id""")) {
                ps.setLong(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) functions.add(new FunctionRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6)));
                }
            }
            return new TaskExtras(hooks, options, functions);
        });
    }

    /**
     * Salva un aggancio. origEvent/origSeq null = nuovo (in coda agli altri dello stesso evento).
     * BUTTON richiede l'etichetta del pulsante.
     */
    public void saveHook(long adminId, long versionId, long taskId, String origEvent, Integer origSeq,
                         String event, String handlerCode, String label, String config) {
        requireAdmin(adminId);
        String ev = oneOf(event, null, "BEFORE_ACTION", "ON_OK", "ON_KO", "ON_ACTIVATE", "BUTTON");
        if (ev == null) throw new ServiceException(Code.INVALID, "Scegliere quando scatta l'aggancio");
        if (blank(handlerCode)) throw new ServiceException(Code.INVALID, "Scegliere l'handler");
        if ("BUTTON".equals(ev) && blank(label)) throw new ServiceException(Code.INVALID, "Il pulsante ha bisogno di un'etichetta");
        String cfg = blank(config) ? "{}" : config.trim();
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            long hid = handlerId(c, handlerCode, "TASK_ACTION");
            if (origEvent != null && origSeq != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM ewf_core.task_hook WHERE task_def_id = ? AND event = ? AND seq = ?")) {
                    ps.setLong(1, taskId);
                    ps.setString(2, origEvent);
                    ps.setInt(3, origSeq);
                    ps.executeUpdate();
                }
            }
            int seq = ev.equals(origEvent) && origSeq != null ? origSeq : nextSeq(c, taskId, ev);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.task_hook(task_def_id, event, seq, handler_id, label, config)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb)""")) {
                ps.setLong(1, taskId);
                ps.setString(2, ev);
                ps.setInt(3, seq);
                ps.setLong(4, hid);
                ps.setString(5, trimToNull(label));
                ps.setString(6, cfg);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    public void deleteHook(long adminId, long versionId, long taskId, String event, int seq) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM ewf_core.task_hook WHERE task_def_id = ? AND event = ? AND seq = ?")) {
                ps.setLong(1, taskId);
                ps.setString(2, event);
                ps.setInt(3, seq);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    /**
     * Salva un'opzione di un nodo di scelta. origCode null = nuova (in fondo). Se il codice cambia, gli step del
     * ramo (varianti) seguono il nuovo codice. Il codice dev'essere unico nella versione (lo controlla anche la
     * validazione).
     */
    public void saveOption(long adminId, long versionId, long taskId, String origCode, OptionRow o) {
        requireAdmin(adminId);
        String code = o.code() == null ? "" : o.code().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{1,10}"))
            throw new ServiceException(Code.INVALID, "Codice dell'opzione: da 1 a 10 tra lettere, cifre e _");
        if (blank(o.label())) throw new ServiceException(Code.INVALID, "L'opzione ha bisogno di un testo");
        String spawn = trimToNull(o.spawnMode());
        String start = trimToNull(o.startProcess());
        if (spawn != null && !List.of("HANDOFF", "PARALLEL").contains(spawn))
            throw new ServiceException(Code.INVALID, "Modo di avvio non valido: " + spawn);
        if ((spawn == null) != (start == null))
            throw new ServiceException(Code.INVALID, "Per avviare un processo servono sia il tipo processo sia il modo");
        if (o.reject() && spawn != null)
            throw new ServiceException(Code.INVALID, "Un'opzione di rifiuto non avvia altri processi");
        String cfg = blank(o.config()) ? "{}" : o.config().trim();
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            if (!code.equals(origCode)
                    && !ids(c, "SELECT o.task_def_id FROM ewf_core.task_option o JOIN ewf_core.task_def d ON d.id = o.task_def_id"
                    + " JOIN ewf_core.step_def s ON s.id = d.step_id WHERE s.version_id = ? AND o.option_code = '" + code + "'",
                    versionId).isEmpty())
                throw new ServiceException(Code.INVALID, "Il codice " + code + " e' gia' usato da un'altra opzione della versione");
            Long hid = blank(o.handlerCode()) ? null : handlerId(c, o.handlerCode(), "TASK_ACTION");
            int seq;
            if (origCode == null) {
                seq = (int) maxOf(c, "SELECT coalesce(max(seq), 0) FROM ewf_core.task_option WHERE task_def_id = ?", taskId) + 1;
            } else {
                seq = (int) maxOf(c, "SELECT coalesce(max(seq), 0) FROM ewf_core.task_option WHERE task_def_id = ? AND option_code = '"
                        + origCode.replace("'", "") + "'", taskId);
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM ewf_core.task_option WHERE task_def_id = ? AND option_code = ?")) {
                    ps.setLong(1, taskId);
                    ps.setString(2, origCode);
                    if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Opzione non trovata");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.task_option(task_def_id, option_code, seq, handler_id, label, reject, config,
                                                     start_process_type, spawn_mode)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""")) {
                ps.setLong(1, taskId);
                ps.setString(2, code);
                ps.setInt(3, seq);
                setLong(ps, 4, hid);
                ps.setString(5, o.label().trim());
                ps.setBoolean(6, o.reject());
                ps.setString(7, cfg);
                ps.setString(8, start);
                ps.setString(9, spawn);
                ps.executeUpdate();
            }
            if (origCode != null && !origCode.equals(code)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE ewf_core.step_def SET option_code = ? WHERE version_id = ? AND option_code = ?")) {
                    ps.setString(1, code);
                    ps.setLong(2, versionId);
                    ps.setString(3, origCode);
                    ps.executeUpdate();
                }
            }
            return null;
        }));
    }

    /** Elimina un'opzione. Rifiutato se ci sono ancora step del suo ramo. */
    public void deleteOption(long adminId, long versionId, long taskId, String code) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT step_no FROM ewf_core.step_def WHERE version_id = ? AND option_code = ? ORDER BY step_no")) {
                ps.setLong(1, versionId);
                ps.setString(2, code);
                List<String> steps = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) steps.add(rs.getString(1));
                }
                if (!steps.isEmpty())
                    throw new ServiceException(Code.INVALID, "Il ramo " + code + " ha ancora gli step " + String.join(", ", steps)
                            + ": eliminarli o spostarli prima di togliere l'opzione");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM ewf_core.task_option WHERE task_def_id = ? AND option_code = ?")) {
                ps.setLong(1, taskId);
                ps.setString(2, code);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    /** Salva una funzione speciale (id null = nuova, in fondo). */
    public void saveFunction(long adminId, long versionId, long taskId, FunctionRow f) {
        requireAdmin(adminId);
        String code = f.code() == null ? "" : f.code().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{1,30}")) throw new ServiceException(Code.INVALID, "Codice della funzione: lettere, cifre e _");
        if (blank(f.label())) throw new ServiceException(Code.INVALID, "La funzione ha bisogno dell'etichetta del pulsante");
        if (blank(f.handlerCode())) throw new ServiceException(Code.INVALID, "Scegliere l'handler della funzione");
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            handlerId(c, f.handlerCode(), "TASK_ACTION");
            if (f.id() == 0) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO ewf_core.task_function(task_def_id, code, label, handler_code, confirm_text, position)
                        VALUES (?, ?, ?, ?, ?, (SELECT coalesce(max(position), 0) + 1 FROM ewf_core.task_function
                                                 WHERE task_def_id = ?))""")) {
                    ps.setLong(1, taskId);
                    ps.setString(2, code);
                    ps.setString(3, f.label().trim());
                    ps.setString(4, f.handlerCode().trim());
                    ps.setString(5, trimToNull(f.confirmText()));
                    ps.setLong(6, taskId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE ewf_core.task_function SET code = ?, label = ?, handler_code = ?, confirm_text = ?
                         WHERE id = ? AND task_def_id = ?""")) {
                    ps.setString(1, code);
                    ps.setString(2, f.label().trim());
                    ps.setString(3, f.handlerCode().trim());
                    ps.setString(4, trimToNull(f.confirmText()));
                    ps.setLong(5, f.id());
                    ps.setLong(6, taskId);
                    if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Funzione non trovata");
                }
            }
            return null;
        }));
    }

    public void deleteFunction(long adminId, long versionId, long taskId, long functionId) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            requireDraft(c, versionId);
            requireTaskInVersion(c, versionId, taskId);
            update(c, "DELETE FROM ewf_core.task_function WHERE id = ? AND task_def_id = ?", functionId, taskId);
            return null;
        }));
    }

    /** Tipi processo, per le opzioni che ne avviano un altro. */
    public List<Choice> processTypes(long adminId) {
        requireAdmin(adminId);
        return choices("SELECT code, description FROM ewf_core.process_type ORDER BY code");
    }

    private static int nextSeq(Connection c, long taskId, String event) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT coalesce(max(seq), 0) + 1 FROM ewf_core.task_hook WHERE task_def_id = ? AND event = ?")) {
            ps.setLong(1, taskId);
            ps.setString(2, event);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static long maxOf(Connection c, String sql, long param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static String emptyJson(String j) {
        return j == null || j.equals("{}") ? "" : j;
    }

    // ================================================================== tipi processo e tipi documento

    /**
     * Proprieta' di un tipo processo. newDocTypeDescription: se docType non esiste ancora, lo si crea con questa
     * descrizione (senza collegamento a un sistema di origine: i dati arrivano dalla richiesta).
     * listColumns: una colonna per riga, "Etichetta = chiave".
     */
    public record TypeEdit(String code, String description, String docType, String newDocTypeDescription,
                           boolean manualStart, boolean suspended, String listSummary, String listColumns,
                           int slaYellowDays, int slaRedDays, String attachmentScope) {}

    public List<Choice> docTypes(long adminId) {
        requireAdmin(adminId);
        return choices("""
                SELECT d.code, d.description || CASE WHEN d.adapter_handler_id IS NULL THEN ' (senza sistema di origine)'
                                                     ELSE '' END
                  FROM ewf_core.doc_type d ORDER BY d.code""");
    }

    public TypeEdit typeSettings(long adminId, String code) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT code, description, doc_type, manual_start, suspended, list_summary, list_columns::text,
                           sla_yellow_days, sla_red_days, attachment_scope
                      FROM ewf_core.process_type WHERE code = ?""")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Tipo processo inesistente");
                    return new TypeEdit(rs.getString(1), rs.getString(2), rs.getString(3), null, rs.getBoolean(4),
                            rs.getBoolean(5), rs.getString(6), columnsText(rs.getString(7)), rs.getInt(8), rs.getInt(9),
                            rs.getString(10));
                }
            }
        });
    }

    /** Crea il tipo processo (ed eventualmente il tipo documento) con la versione 1 vuota in bozza. @return id della bozza */
    public long createProcessType(long adminId, TypeEdit e) {
        requireAdmin(adminId);
        String code = e.code() == null ? "" : e.code().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{2,20}"))
            throw new ServiceException(Code.INVALID, "Codice del tipo processo: da 2 a 20 tra lettere, cifre e _");
        String docType = e.docType() == null ? "" : e.docType().trim().toUpperCase();
        if (!docType.matches("[A-Z0-9_]{1,20}"))
            throw new ServiceException(Code.INVALID, "Tipo documento: da 1 a 20 tra lettere, cifre e _");
        checkType(e);
        String columns = columnsJson(e.listColumns());
        return tx(() -> db.with(c -> {
            if (count(c, "SELECT count(*) FROM ewf_core.process_type WHERE code = ?", code) > 0)
                throw new ServiceException(Code.INVALID, "Il tipo processo " + code + " esiste gia'");
            if (count(c, "SELECT count(*) FROM ewf_core.doc_type WHERE code = ?", docType) == 0) {
                if (blank(e.newDocTypeDescription()))
                    throw new ServiceException(Code.INVALID, "Il tipo documento " + docType + " non esiste: indicarne la descrizione"
                            + " per crearlo");
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO ewf_core.doc_type(code, description) VALUES (?, ?)")) {
                    ps.setString(1, docType);
                    ps.setString(2, e.newDocTypeDescription().trim());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.process_type(code, description, doc_type, manual_start, suspended, list_summary,
                           list_columns, sla_yellow_days, sla_red_days, attachment_scope)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""")) {
                ps.setString(1, code);
                ps.setString(2, e.description().trim());
                ps.setString(3, docType);
                ps.setBoolean(4, e.manualStart());
                ps.setBoolean(5, e.suspended());
                ps.setString(6, trimToNull(e.listSummary()));
                ps.setString(7, columns);
                ps.setInt(8, e.slaYellowDays());
                ps.setInt(9, e.slaRedDays());
                ps.setString(10, e.attachmentScope());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.process_version(process_type_code, version_no, status, created_by, note)
                    VALUES (?, 1, 'DRAFT', ?, 'Prima versione') RETURNING id""")) {
                ps.setString(1, code);
                ps.setLong(2, adminId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }));
    }

    /** Modifica le proprieta' di un tipo processo (codice e tipo documento non cambiano). */
    public void saveProcessType(long adminId, TypeEdit e) {
        requireAdmin(adminId);
        checkType(e);
        String columns = columnsJson(e.listColumns());
        tx(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.process_type SET description = ?, manual_start = ?, suspended = ?, list_summary = ?,
                           list_columns = ?::jsonb, sla_yellow_days = ?, sla_red_days = ?, attachment_scope = ?
                     WHERE code = ?""")) {
                ps.setString(1, e.description().trim());
                ps.setBoolean(2, e.manualStart());
                ps.setBoolean(3, e.suspended());
                ps.setString(4, trimToNull(e.listSummary()));
                ps.setString(5, columns);
                ps.setInt(6, e.slaYellowDays());
                ps.setInt(7, e.slaRedDays());
                ps.setString(8, e.attachmentScope());
                ps.setString(9, e.code());
                if (ps.executeUpdate() == 0) throw new ServiceException(Code.NOT_FOUND, "Tipo processo inesistente");
            }
            return null;
        }));
    }

    private static void checkType(TypeEdit e) {
        if (blank(e.description())) throw new ServiceException(Code.INVALID, "La descrizione e' obbligatoria");
        if (e.description().trim().length() > 80) throw new ServiceException(Code.INVALID, "Descrizione: al massimo 80 caratteri");
        if (e.slaYellowDays() <= 0 || e.slaRedDays() <= e.slaYellowDays())
            throw new ServiceException(Code.INVALID, "Semaforo: il giallo deve essere almeno 1 giorno e il rosso dopo il giallo");
        if (!List.of("PROCESS", "DOCUMENT", "BOTH").contains(e.attachmentScope()))
            throw new ServiceException(Code.INVALID, "Modalita' degli allegati non valida");
        if (e.listSummary() != null && e.listSummary().length() > 500)
            throw new ServiceException(Code.INVALID, "Riepilogo: al massimo 500 caratteri");
    }

    /** "Etichetta = chiave" per riga -> [{"label":"...","key":"..."}]. */
    static String columnsJson(String text) {
        StringBuilder b = new StringBuilder("[");
        if (text != null)
            for (String line : text.split("\\R")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                int i = l.indexOf('=');
                if (i <= 0 || i == l.length() - 1)
                    throw new ServiceException(Code.INVALID, "Colonne: una per riga, \"Etichetta = chiave\" (riga: " + l + ")");
                if (b.length() > 1) b.append(',');
                b.append("{\"label\":").append(StartRuleService.quote(l.substring(0, i).trim()))
                 .append(",\"key\":").append(StartRuleService.quote(l.substring(i + 1).trim()))
                 .append('}');
            }
        return b.append(']').toString();
    }

    /** Il contrario di {@link #columnsJson}, letto con PostgreSQL per non dipendere da una libreria JSON. */
    private String columnsText(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return "";
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e->>'label', e->>'key' FROM jsonb_array_elements(?::jsonb) e")) {
                ps.setString(1, json);
                StringBuilder b = new StringBuilder();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) b.append(rs.getString(1)).append(" = ").append(rs.getString(2)).append('\n');
                }
                return b.toString();
            }
        });
    }

    private static long count(Connection c, String sql, String p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
