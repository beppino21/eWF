package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.model.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrive tipi processo e versioni nel DB. Base per l'estrattore di migrazione da /EWF/
 * e per la futura UI di amministrazione. Gli id delle definizioni li genera il DB:
 * gli id presenti negli oggetti Java passati vengono ignorati.
 * Crea al volo le anagrafiche mancanti (tipo documento, ruoli, team, handler).
 */
public final class JdbcDefinitionWriter {

    private final JdbcTransactions db;

    public JdbcDefinitionWriter(JdbcTransactions db) {
        this.db = db;
    }

    public void saveType(ProcessType t) {
        db.run(() -> db.with(c -> {
            ensure(c, "INSERT INTO ewf_core.doc_type(code, description) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    t.docType(), t.docType());
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.process_type(code, description, doc_type, suspended, auto_ok_first, no_first_step)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description, doc_type = EXCLUDED.doc_type,
                        suspended = EXCLUDED.suspended, auto_ok_first = EXCLUDED.auto_ok_first,
                        no_first_step = EXCLUDED.no_first_step""")) {
                ps.setString(1, t.code());
                ps.setString(2, t.description() == null ? t.code() : t.description());
                ps.setString(3, t.docType());
                ps.setBoolean(4, t.suspended());
                ps.setBoolean(5, t.autoOkFirst());
                ps.setBoolean(6, t.noFirstStep());
                ps.executeUpdate();
            }
            return null;
        }));
    }

    /**
     * Salva la versione come nuova (numero successivo per il tipo processo).
     * Se activate: la valida con ewf_core.validate_version, ritira la versione attiva e attiva questa.
     * @return id della versione nel DB
     */
    public long saveVersion(ProcessVersion v, boolean activate) {
        return db.inTransaction(() -> db.with(c -> {
            Long endHandler = v.endHandlerCode().isPresent() ? handlerId(c, v.endHandlerCode().get(), "PROCESS_END") : null;
            long versionId;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.process_version(process_type_code, version_no, status, end_handler_id)
                    VALUES (?, (SELECT coalesce(max(version_no), 0) + 1 FROM ewf_core.process_version
                                 WHERE process_type_code = ?), 'DRAFT', ?)
                    RETURNING id""")) {
                ps.setString(1, v.processTypeCode());
                ps.setString(2, v.processTypeCode());
                Sql.setLong(ps, 3, endHandler);
                versionId = single(ps);
            }

            for (StepDef s : v.steps()) {
                long stepId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO ewf_core.step_def(version_id, step_no, is_first, join_policy, next_step_ok,
                                                      next_step_ko, min_amount, min_amount_currency)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                    ps.setLong(1, versionId);
                    ps.setInt(2, s.stepNo());
                    ps.setBoolean(3, s.first());
                    ps.setString(4, s.join().name());
                    Sql.setInt(ps, 5, s.nextOk());
                    Sql.setInt(ps, 6, s.nextKo());
                    Sql.setBigDecimal(ps, 7, s.minAmount() == null ? null : s.minAmount().amount());
                    ps.setString(8, s.minAmount() == null ? null : s.minAmount().currency());
                    stepId = single(ps);
                }
                for (TaskDef t : s.tasks()) {
                    if (t.roleCode() != null)
                        ensure(c, "INSERT INTO ewf_org.role(code, description) VALUES (?, ?) ON CONFLICT DO NOTHING",
                                t.roleCode(), t.roleCode());
                    if (t.teamCode() != null)
                        ensure(c, "INSERT INTO ewf_org.team(code, description) VALUES (?, ?) ON CONFLICT DO NOTHING",
                                t.teamCode(), t.teamCode());
                    Long assigneeHandler = t.assigneeHandlerCode() == null ? null
                            : handlerId(c, t.assigneeHandlerCode(), "ASSIGNEE");
                    long taskId;
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO ewf_core.task_def(step_id, subop_no, text, role_code, required_weight,
                                   assignee_mode, assignee_handler_id, team_task, team_code, preview_mode,
                                   interaction, task_param)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                        ps.setLong(1, stepId);
                        ps.setInt(2, t.subopNo());
                        ps.setString(3, t.text());
                        ps.setString(4, t.roleCode());
                        ps.setInt(5, t.requiredWeight());
                        ps.setString(6, t.assigneeMode().name());
                        Sql.setLong(ps, 7, assigneeHandler);
                        ps.setBoolean(8, t.teamTask());
                        ps.setString(9, t.teamCode());
                        ps.setString(10, t.previewMode().name());
                        ps.setString(11, t.interaction().name());
                        ps.setString(12, t.taskParam());
                        taskId = single(ps);
                    }
                    for (HookDef h : t.hooks()) {
                        long hid = handlerId(c, h.handlerCode(), "TASK_ACTION");
                        try (PreparedStatement ps = c.prepareStatement("""
                                INSERT INTO ewf_core.task_hook(task_def_id, event, seq, handler_id, label, config)
                                VALUES (?, ?, ?, ?, ?, ?::jsonb)""")) {
                            ps.setLong(1, taskId);
                            ps.setString(2, h.event().name());
                            ps.setInt(3, h.seq());
                            ps.setLong(4, hid);
                            ps.setString(5, h.label());
                            ps.setString(6, Sql.json(h.config()));
                            ps.executeUpdate();
                        }
                    }
                }
            }

            if (activate) activate(c, versionId, v.processTypeCode());
            return versionId;
        }));
    }

    private void activate(Connection c, long versionId, String type) throws SQLException {
        List<String> errors = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT severity, step_no, problem FROM ewf_core.validate_version(?) WHERE severity = 'ERROR'")) {
            ps.setLong(1, versionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) errors.add("step " + rs.getString(2) + ": " + rs.getString(3));
            }
        }
        if (!errors.isEmpty())
            throw new JdbcException("Versione non attivabile: " + String.join("; ", errors));
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ewf_core.process_version SET status = 'RETIRED' WHERE process_type_code = ? AND status = 'ACTIVE'")) {
            ps.setString(1, type);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE ewf_core.process_version SET status = 'ACTIVE', activated_at = now() WHERE id = ?")) {
            ps.setLong(1, versionId);
            ps.executeUpdate();
        }
    }

    /** Id dell'handler nel catalogo; se manca lo crea come JAVA (da completare in amministrazione). */
    private long handlerId(Connection c, String code, String contract) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM ewf_core.handler WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO ewf_core.handler(code, kind, contract, target, description)
                VALUES (?, 'JAVA', ?, ?, 'creato automaticamente') RETURNING id""")) {
            ps.setString(1, code);
            ps.setString(2, contract);
            ps.setString(3, code);
            return single(ps);
        }
    }

    private static void ensure(Connection c, String sql, String a, String b) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a);
            ps.setString(2, b);
            ps.executeUpdate();
        }
    }

    private static long single(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
