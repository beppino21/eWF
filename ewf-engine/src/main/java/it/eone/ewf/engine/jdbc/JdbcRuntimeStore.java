package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.RuntimeStore;

import java.sql.*;
import java.util.*;

/** Runtime su ewf_core. Gli update usano row_version per il lock ottimistico. */
public final class JdbcRuntimeStore implements RuntimeStore {

    private final JdbcTransactions db;

    public JdbcRuntimeStore(JdbcTransactions db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ processi

    private static final String P_COLS = """
            id, version_id, process_type_code, backend_code, doc_type, document_key, status, status_reason,
            currency, net_value, started_at, started_by, ended_at, ended_by, correlation_key, row_version,
            ARRAY(SELECT e.key || chr(31) || e.value FROM jsonb_each_text(doc_context) e) AS doc_context_kv""";

    @Override
    public ProcessInstance insertProcess(ProcessInstance p) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.process_instance(version_id, process_type_code, backend_code, doc_type,
                           document_key, status, status_reason, currency, net_value, started_at, started_by,
                           ended_at, ended_by, correlation_key, doc_context, row_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 0) RETURNING id""")) {
                ps.setLong(1, p.versionId);
                ps.setString(2, p.processTypeCode);
                ps.setString(3, p.backendCode);
                ps.setString(4, p.docType);
                ps.setString(5, p.documentKey);
                ps.setString(6, p.status.name());
                ps.setString(7, p.statusReason);
                ps.setString(8, p.value == null ? null : p.value.currency());
                Sql.setBigDecimal(ps, 9, p.value == null ? null : p.value.amount());
                Sql.setInstant(ps, 10, p.startedAt);
                ps.setLong(11, p.startedBy);
                Sql.setInstant(ps, 12, p.endedAt);
                Sql.setLong(ps, 13, p.endedBy);
                ps.setObject(14, p.correlationKey);
                ps.setString(15, Sql.json(p.docContext));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    p.id = rs.getLong(1);
                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState()) && String.valueOf(e.getMessage()).contains("process_instance_one_open"))
                        throw new DuplicateOpenProcessException(p.processTypeCode + " " + p.documentKey);
                    throw e;
                }
            }
            p.rowVersion = 0;
            return p.copy();
        });
    }

    @Override
    public void updateProcess(ProcessInstance p) {
        int n = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.process_instance
                       SET status = ?, status_reason = ?, currency = ?, net_value = ?, ended_at = ?, ended_by = ?,
                           row_version = row_version + 1
                     WHERE id = ? AND row_version = ?""")) {
                ps.setString(1, p.status.name());
                ps.setString(2, p.statusReason);
                ps.setString(3, p.value == null ? null : p.value.currency());
                Sql.setBigDecimal(ps, 4, p.value == null ? null : p.value.amount());
                Sql.setInstant(ps, 5, p.endedAt);
                Sql.setLong(ps, 6, p.endedBy);
                ps.setLong(7, p.id);
                ps.setInt(8, p.rowVersion);
                return ps.executeUpdate();
            }
        });
        if (n != 1) throw new OptimisticLockException("process " + p.id);
        p.rowVersion++;
    }

    @Override
    public void lockProcess(long processId) {
        if (!db.inTransaction()) throw new JdbcException("lockProcess richiede una transazione");
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM ewf_core.process_instance WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public Optional<ProcessInstance> process(long id) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + P_COLS + " FROM ewf_core.process_instance WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(process(rs)) : Optional.empty();
                }
            }
        });
    }

    private static ProcessInstance process(ResultSet rs) throws SQLException {
        ProcessInstance p = new ProcessInstance();
        p.id = rs.getLong("id");
        p.versionId = rs.getLong("version_id");
        p.processTypeCode = rs.getString("process_type_code");
        p.backendCode = rs.getString("backend_code");
        p.docType = rs.getString("doc_type");
        p.documentKey = rs.getString("document_key");
        p.status = ProcessStatus.valueOf(rs.getString("status"));
        p.statusReason = rs.getString("status_reason");
        p.value = Sql.getMoney(rs, "net_value", "currency");
        p.startedAt = Sql.getInstant(rs, "started_at");
        p.startedBy = rs.getLong("started_by");
        p.endedAt = Sql.getInstant(rs, "ended_at");
        p.endedBy = Sql.getLong(rs, "ended_by");
        p.correlationKey = Sql.getUuid(rs, "correlation_key");
        p.rowVersion = rs.getInt("row_version");
        java.util.Map<String, String> ctx = new java.util.LinkedHashMap<>();
        java.sql.Array arr = rs.getArray("doc_context_kv");
        if (arr != null)
            for (Object o : (Object[]) arr.getArray()) {
                String kv = (String) o;
                int sep = kv.indexOf('\u001f');
                ctx.put(kv.substring(0, sep), kv.substring(sep + 1));
            }
        p.docContext = java.util.Map.copyOf(ctx);
        return p;
    }

    @Override
    public boolean hasOpenProcess(String type, String docType, String key, String backend) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT 1 FROM ewf_core.process_instance
                     WHERE process_type_code = ? AND doc_type = ? AND document_key = ?
                       AND backend_code IS NOT DISTINCT FROM ?
                       AND status IN ('RUNNING','SUSPENDED','COMPLETING','ERROR')""")) {
                ps.setString(1, type);
                ps.setString(2, docType);
                ps.setString(3, key);
                ps.setString(4, backend);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    // ------------------------------------------------------------------ task

    private static final String T_COLS = """
            id, process_id, task_def_id, step_no, subop_no, status, assignee_id, team_code, claimed_by_id,
            claimed_at, role_code, from_user_id, moved_to_id, acted_by_id, acted_as_subst, action, note,
            created_at, closed_at, option_code, row_version""";

    @Override
    public TaskInstance insertTask(TaskInstance t) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.task_instance(process_id, task_def_id, step_no, subop_no, status, assignee_id,
                           team_code, claimed_by_id, claimed_at, role_code, from_user_id, moved_to_id, acted_by_id,
                           acted_as_subst, action, note, created_at, closed_at, option_code, row_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0) RETURNING id""")) {
                ps.setLong(1, t.processId);
                ps.setLong(2, t.taskDefId);
                bindTask(ps, 3, t);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    t.id = rs.getLong(1);
                }
            }
            t.rowVersion = 0;
            return t.copy();
        });
    }

    /** Lega step_no ... closed_at, option_code (17 parametri) a partire da i. */
    private static void bindTask(PreparedStatement ps, int i, TaskInstance t) throws SQLException {
        ps.setInt(i, t.stepNo);
        ps.setInt(i + 1, t.subopNo);
        ps.setString(i + 2, t.status.name());
        Sql.setLong(ps, i + 3, t.assigneeId);
        ps.setString(i + 4, t.teamCode);
        Sql.setLong(ps, i + 5, t.claimedById);
        Sql.setInstant(ps, i + 6, t.claimedAt);
        ps.setString(i + 7, t.roleCode);
        Sql.setLong(ps, i + 8, t.fromUserId);
        Sql.setLong(ps, i + 9, t.movedToId);
        Sql.setLong(ps, i + 10, t.actedById);
        ps.setBoolean(i + 11, t.actedAsSubstitute);
        ps.setString(i + 12, t.action);
        ps.setString(i + 13, t.note);
        Sql.setInstant(ps, i + 14, t.createdAt);
        Sql.setInstant(ps, i + 15, t.closedAt);
        ps.setString(i + 16, t.optionCode);
    }

    @Override
    public void updateTask(TaskInstance t) {
        int n = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.task_instance
                       SET step_no = ?, subop_no = ?, status = ?, assignee_id = ?, team_code = ?, claimed_by_id = ?,
                           claimed_at = ?, role_code = ?, from_user_id = ?, moved_to_id = ?, acted_by_id = ?,
                           acted_as_subst = ?, action = ?, note = ?, created_at = ?, closed_at = ?,
                           option_code = ?, row_version = row_version + 1
                     WHERE id = ? AND row_version = ?""")) {
                bindTask(ps, 1, t);
                ps.setLong(18, t.id);
                ps.setInt(19, t.rowVersion);
                return ps.executeUpdate();
            }
        });
        if (n != 1) throw new OptimisticLockException("task " + t.id);
        t.rowVersion++;
    }

    @Override
    public Optional<TaskInstance> task(long id) {
        List<TaskInstance> l = tasks("WHERE id = ?", ps -> ps.setLong(1, id));
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    @Override
    public List<TaskInstance> tasksOf(long processId) {
        return tasks("WHERE process_id = ? ORDER BY id", ps -> ps.setLong(1, processId));
    }

    @Override
    public List<TaskInstance> openTasksFor(Set<Long> assigneeIds, Set<String> teamCodes) {
        return db.with(c -> {
            Array users = c.createArrayOf("bigint", assigneeIds.toArray());
            Array teams = c.createArrayOf("varchar", teamCodes.toArray());
            return tasks(c, "WHERE status = 'OPEN' AND (assignee_id = ANY(?) OR team_code = ANY(?)) ORDER BY created_at, id",
                    ps -> { ps.setArray(1, users); ps.setArray(2, teams); });
        });
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<TaskInstance> tasks(String where, Binder binder) {
        return db.with(c -> tasks(c, where, binder));
    }

    private static List<TaskInstance> tasks(Connection c, String where, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + T_COLS + " FROM ewf_core.task_instance " + where)) {
            binder.bind(ps);
            List<TaskInstance> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TaskInstance t = new TaskInstance();
                    t.id = rs.getLong("id");
                    t.processId = rs.getLong("process_id");
                    t.taskDefId = rs.getLong("task_def_id");
                    t.stepNo = rs.getInt("step_no");
                    t.subopNo = rs.getInt("subop_no");
                    t.status = TaskStatus.valueOf(rs.getString("status"));
                    t.assigneeId = Sql.getLong(rs, "assignee_id");
                    t.teamCode = rs.getString("team_code");
                    t.claimedById = Sql.getLong(rs, "claimed_by_id");
                    t.claimedAt = Sql.getInstant(rs, "claimed_at");
                    t.roleCode = rs.getString("role_code");
                    t.fromUserId = Sql.getLong(rs, "from_user_id");
                    t.movedToId = Sql.getLong(rs, "moved_to_id");
                    t.actedById = Sql.getLong(rs, "acted_by_id");
                    t.actedAsSubstitute = rs.getBoolean("acted_as_subst");
                    t.action = rs.getString("action");
                    t.note = rs.getString("note");
                    t.createdAt = Sql.getInstant(rs, "created_at");
                    t.closedAt = Sql.getInstant(rs, "closed_at");
                    t.optionCode = rs.getString("option_code");
                    t.rowVersion = rs.getInt("row_version");
                    out.add(t);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ stato step

    @Override
    public Map<Integer, StepState> stepStates(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT step_no, skipped, reason, source_execution_id
                      FROM ewf_core.instance_step_state WHERE process_id = ?""")) {
                ps.setLong(1, processId);
                Map<Integer, StepState> out = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getInt(1), new StepState(processId, rs.getInt(1), rs.getBoolean(2),
                                rs.getString(3), Sql.getLong(rs, "source_execution_id")));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public void putStepState(StepState s) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.instance_step_state(process_id, step_no, skipped, reason, source_execution_id)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (process_id, step_no) DO UPDATE
                       SET skipped = EXCLUDED.skipped, reason = EXCLUDED.reason,
                           source_execution_id = EXCLUDED.source_execution_id, changed_at = now()""")) {
                ps.setLong(1, s.processId());
                ps.setInt(2, s.stepNo());
                ps.setBoolean(3, s.skipped());
                ps.setString(4, s.reason());
                Sql.setLong(ps, 5, s.sourceExecutionId());
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void clearStepStates(long processId, String reason) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM ewf_core.instance_step_state WHERE process_id = ? AND reason = ?")) {
                ps.setLong(1, processId);
                ps.setString(2, reason);
                return ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ esecuzioni handler

    @Override
    public HandlerExecution insertExecution(HandlerExecution e) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.handler_execution(task_id, process_id, handler_code, handler_id, event, attempt,
                           idempotency_key, status, result_message, directives, started_at, ended_at)
                    VALUES (?, ?, ?, (SELECT id FROM ewf_core.handler WHERE code = ?), ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    RETURNING id""")) {
                Sql.setLong(ps, 1, e.taskId);
                ps.setLong(2, e.processId);
                ps.setString(3, e.handlerCode);
                ps.setString(4, e.handlerCode);
                ps.setString(5, e.event);
                ps.setInt(6, e.attempt);
                ps.setObject(7, e.idempotencyKey);
                ps.setString(8, e.status.name());
                ps.setString(9, truncate(e.message, 255));
                ps.setString(10, directives(e.directives));
                Sql.setInstant(ps, 11, e.startedAt);
                Sql.setInstant(ps, 12, e.endedAt);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    e.id = rs.getLong(1);
                }
            }
            return e;
        });
    }

    @Override
    public void updateExecution(HandlerExecution e) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE ewf_core.handler_execution
                       SET status = ?, result_message = ?, directives = ?::jsonb, ended_at = ?, attempt = ?
                     WHERE id = ?""")) {
                ps.setString(1, e.status.name());
                ps.setString(2, truncate(e.message, 255));
                ps.setString(3, directives(e.directives));
                Sql.setInstant(ps, 4, e.endedAt);
                ps.setInt(5, e.attempt);
                ps.setLong(6, e.id);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public Optional<HandlerExecution> execution(long id) {
        List<HandlerExecution> l = executions("WHERE id = ?", id);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    @Override
    public List<HandlerExecution> executionsOf(long processId) {
        return executions("WHERE process_id = ? ORDER BY id", processId);
    }

    private List<HandlerExecution> executions(String where, long param) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, task_id, process_id, handler_code, event, attempt, idempotency_key, status,
                           result_message, directives::text AS directives, started_at, ended_at
                      FROM ewf_core.handler_execution\s""" + where)) {
                ps.setLong(1, param);
                List<HandlerExecution> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        HandlerExecution e = new HandlerExecution();
                        e.id = rs.getLong("id");
                        e.taskId = Sql.getLong(rs, "task_id");
                        e.processId = rs.getLong("process_id");
                        e.handlerCode = rs.getString("handler_code");
                        e.event = rs.getString("event");
                        e.attempt = rs.getInt("attempt");
                        e.idempotencyKey = Sql.getUuid(rs, "idempotency_key");
                        e.status = HandlerExecution.Status.valueOf(rs.getString("status"));
                        e.message = rs.getString("result_message");
                        String d = rs.getString("directives");
                        e.directives = d == null || d.equals("[]") ? null : d;
                        e.startedAt = Sql.getInstant(rs, "started_at");
                        e.endedAt = Sql.getInstant(rs, "ended_at");
                        out.add(e);
                    }
                }
                return out;
            }
        });
    }

    private static String directives(String d) {
        return d == null ? "[]" : Sql.jsonArray(d);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ------------------------------------------------------------------ documenti e collegamenti

    @Override
    public void insertDocument(GeneratedDocument d) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.generated_document(process_id, task_id, execution_id, doc_cat, doc_number,
                           doc_year, company_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setLong(1, d.processId());
                Sql.setLong(ps, 2, d.taskId());
                Sql.setLong(ps, 3, d.executionId());
                ps.setString(4, d.docCat());
                ps.setString(5, d.docNumber());
                ps.setString(6, d.docYear());
                ps.setString(7, d.companyCode());
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public List<GeneratedDocument> documentsOf(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT process_id, task_id, execution_id, doc_cat, doc_number, doc_year, company_code
                      FROM ewf_core.generated_document WHERE process_id = ? ORDER BY id""")) {
                ps.setLong(1, processId);
                List<GeneratedDocument> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new GeneratedDocument(rs.getLong(1), Sql.getLong(rs, "task_id"),
                                Sql.getLong(rs, "execution_id"), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getString(7)));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public void insertLink(ProcessLink l) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ewf_core.process_link(parent_id, child_id, link_type) VALUES (?, ?, ?)")) {
                ps.setLong(1, l.parentId());
                ps.setLong(2, l.childId());
                ps.setString(3, l.linkType());
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public List<ProcessLink> links(long processId) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT parent_id, child_id, link_type FROM ewf_core.process_link
                     WHERE parent_id = ? OR child_id = ?""")) {
                ps.setLong(1, processId);
                ps.setLong(2, processId);
                List<ProcessLink> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new ProcessLink(rs.getLong(1), rs.getLong(2), rs.getString(3)));
                }
                return out;
            }
        });
    }
}
