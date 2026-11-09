package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.DefinitionStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Definizioni da ewf_core. Le versioni usate dal motore sono immutabili (ACTIVE o RETIRED),
 * quindi restano in cache; la versione attiva invece si rilegge ogni volta.
 */
public final class JdbcDefinitionStore implements DefinitionStore {

    private final JdbcTransactions db;
    private final Map<Long, ProcessVersion> cache = new ConcurrentHashMap<>();

    public JdbcDefinitionStore(JdbcTransactions db) {
        this.db = db;
    }

    @Override
    public Optional<ProcessType> processType(String code) {
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT code, description, doc_type, suspended, auto_ok_first, no_first_step
                      FROM ewf_core.process_type WHERE code = ?""")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(new ProcessType(rs.getString("code"), rs.getString("description"),
                            rs.getString("doc_type"), rs.getBoolean("suspended"),
                            rs.getBoolean("auto_ok_first"), rs.getBoolean("no_first_step")));
                }
            }
        });
    }

    @Override
    public Optional<ProcessVersion> activeVersion(String processTypeCode) {
        Long id = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM ewf_core.process_version WHERE process_type_code = ? AND status = 'ACTIVE'")) {
                ps.setString(1, processTypeCode);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        });
        return id == null ? Optional.empty() : Optional.of(version(id));
    }

    @Override
    public ProcessVersion version(long versionId) {
        return cache.computeIfAbsent(versionId, this::load);
    }

    /** Da chiamare se si modifica una versione non ancora usata (es. bozza riattivata). */
    public void evict(long versionId) {
        cache.remove(versionId);
    }

    /** Lettura diretta, senza cache (usata anche dalla validazione delle bozze). */
    ProcessVersion load(long versionId) {
        return db.with(c -> {
            String type;
            int versionNo;
            String endHandler;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT v.process_type_code, v.version_no, h.code AS end_handler
                      FROM ewf_core.process_version v
                      LEFT JOIN ewf_core.handler h ON h.id = v.end_handler_id
                     WHERE v.id = ?""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new JdbcException("Versione " + versionId + " inesistente");
                    type = rs.getString(1);
                    versionNo = rs.getInt(2);
                    endHandler = rs.getString(3);
                }
            }
            Map<String, String> endConfig = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT e.key, e.value FROM ewf_core.process_version v "
                    + "CROSS JOIN LATERAL jsonb_each_text(v.end_handler_config) e WHERE v.id = ?")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) endConfig.put(rs.getString(1), rs.getString(2));
                }
            }

            // configurazione degli agganci: (task_def_id|event|seq) -> chiave/valore
            Map<String, Map<String, String>> configs = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT k.task_def_id, k.event, k.seq, e.key, e.value
                      FROM ewf_core.task_hook k
                      JOIN ewf_core.task_def t ON t.id = k.task_def_id
                      JOIN ewf_core.step_def s ON s.id = t.step_id
                     CROSS JOIN LATERAL jsonb_each_text(k.config) e
                     WHERE s.version_id = ?""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        configs.computeIfAbsent(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getInt(3),
                                k -> new LinkedHashMap<>()).put(rs.getString(4), rs.getString(5));
                    }
                }
            }

            Map<Long, List<HookDef>> hooks = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT k.task_def_id, k.event, k.seq, h.code, k.label
                      FROM ewf_core.task_hook k
                      JOIN ewf_core.handler h ON h.id = k.handler_id
                      JOIN ewf_core.task_def t ON t.id = k.task_def_id
                      JOIN ewf_core.step_def s ON s.id = t.step_id
                     WHERE s.version_id = ?
                     ORDER BY k.task_def_id, k.event, k.seq""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long td = rs.getLong(1);
                        String ev = rs.getString(2);
                        int seq = rs.getInt(3);
                        hooks.computeIfAbsent(td, k -> new ArrayList<>()).add(new HookDef(
                                HookEvent.valueOf(ev), seq, rs.getString(4),
                                configs.getOrDefault(td + "|" + ev + "|" + seq, Map.of()), rs.getString(5)));
                    }
                }
            }

            Map<String, Map<String, String>> optionConfigs = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT o.task_def_id, o.option_code, e.key, e.value
                      FROM ewf_core.task_option o
                      JOIN ewf_core.task_def t ON t.id = o.task_def_id
                      JOIN ewf_core.step_def s ON s.id = t.step_id
                     CROSS JOIN LATERAL jsonb_each_text(o.config) e
                     WHERE s.version_id = ?""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        optionConfigs.computeIfAbsent(rs.getLong(1) + "|" + rs.getString(2), k -> new LinkedHashMap<>())
                                .put(rs.getString(3), rs.getString(4));
                }
            }
            Map<Long, List<OptionDef>> options = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT o.task_def_id, o.option_code, o.label, o.reject, h.code AS handler,
                           o.start_process_type, o.spawn_mode
                      FROM ewf_core.task_option o
                      JOIN ewf_core.task_def t ON t.id = o.task_def_id
                      JOIN ewf_core.step_def s ON s.id = t.step_id
                      LEFT JOIN ewf_core.handler h ON h.id = o.handler_id
                     WHERE s.version_id = ?
                     ORDER BY o.task_def_id, o.seq""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        options.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new OptionDef(
                                rs.getString("option_code"), rs.getString("label"), rs.getBoolean("reject"),
                                rs.getString("handler"),
                                optionConfigs.getOrDefault(rs.getLong(1) + "|" + rs.getString("option_code"), Map.of()),
                                rs.getString("start_process_type"),
                                rs.getString("spawn_mode") == null ? null : SpawnMode.valueOf(rs.getString("spawn_mode"))));
                    }
                }
            }

            Map<Long, List<TaskDef>> tasksByStep = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id, t.step_id, t.subop_no, t.text, t.role_code, t.required_weight,
                           t.assignee_mode, h.code AS assignee_handler, t.team_task, t.team_code,
                           t.preview_mode, t.interaction, t.task_param
                      FROM ewf_core.task_def t
                      JOIN ewf_core.step_def s ON s.id = t.step_id
                      LEFT JOIN ewf_core.handler h ON h.id = t.assignee_handler_id
                     WHERE s.version_id = ?
                     ORDER BY t.subop_no""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        tasksByStep.computeIfAbsent(rs.getLong("step_id"), k -> new ArrayList<>()).add(new TaskDef(
                                id, rs.getInt("subop_no"), rs.getString("text"), rs.getString("role_code"),
                                rs.getInt("required_weight"), AssigneeMode.valueOf(rs.getString("assignee_mode")),
                                rs.getString("assignee_handler"), rs.getBoolean("team_task"), rs.getString("team_code"),
                                PreviewMode.valueOf(rs.getString("preview_mode")),
                                Interaction.valueOf(rs.getString("interaction")), rs.getString("task_param"),
                                hooks.getOrDefault(id, List.of()), options.getOrDefault(id, List.of())));
                    }
                }
            }

            List<StepDef> steps = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, step_no, is_first, join_policy, next_step_ok, next_step_ko,
                           min_amount, min_amount_currency, option_code
                      FROM ewf_core.step_def WHERE version_id = ? ORDER BY step_no, option_code NULLS FIRST""")) {
                ps.setLong(1, versionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        steps.add(new StepDef(rs.getInt("step_no"), rs.getBoolean("is_first"),
                                JoinPolicy.valueOf(rs.getString("join_policy")),
                                Sql.getInt(rs, "next_step_ok"), Sql.getInt(rs, "next_step_ko"),
                                Sql.getMoney(rs, "min_amount", "min_amount_currency"),
                                tasksByStep.getOrDefault(rs.getLong("id"), List.of()), rs.getString("option_code")));
                    }
                }
            }
            return new ProcessVersion(versionId, type, versionNo, endHandler, endConfig, steps);
        });
    }
}
