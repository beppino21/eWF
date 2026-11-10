package it.eone.connect.scheduler;

import it.eone.connect.db.Db;
import it.eone.connect.db.Json;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Definizioni dei job e storico delle esecuzioni (schema eone_connect). */
public final class JobStore {

    private final Db db;

    public JobStore(Db db) {
        this.db = db;
    }

    static final String COLS = "code, job_key, description, schedule, zone, enabled, next_run_at, last_idle_at, "
            + Json.select("params", "params_kv");

    /**
     * Registra il job se non esiste. Se esiste non tocca nulla: pianificazione, attivazione e parametri
     * possono essere stati cambiati dall'amministratore e non vanno sovrascritti a ogni avvio.
     */
    public void ensure(String code, String jobKey, String description, String schedule, String zone,
                       boolean enabled, Map<String, String> params) {
        ZoneId z = ZoneId.of(zone);
        Instant first = Schedule.parse(schedule, z).next(Instant.now());
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO eone_connect.job(code, job_key, description, schedule, zone, enabled, params, next_run_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (code) DO NOTHING""")) {
                ps.setString(1, code);
                ps.setString(2, jobKey);
                ps.setString(3, description);
                ps.setString(4, Schedule.parse(schedule, z).text());
                ps.setString(5, zone);
                ps.setBoolean(6, enabled);
                ps.setString(7, Json.of(params == null ? Map.of() : params));
                ps.setTimestamp(8, Timestamp.from(first));
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<JobDefinition> definitions() {
        return db.tx(c -> {
            List<JobDefinition> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + COLS + " FROM eone_connect.job ORDER BY code")) {
                while (rs.next()) out.add(definition(rs));
            }
            return out;
        });
    }

    public Optional<JobDefinition> definition(String code) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM eone_connect.job WHERE code = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(definition(rs)) : Optional.<JobDefinition>empty();
                }
            }
        });
    }

    /** Job attivi da eseguire adesso. */
    public List<JobDefinition> due(Instant now) {
        return db.tx(c -> {
            List<JobDefinition> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLS
                    + " FROM eone_connect.job WHERE enabled AND next_run_at <= ? ORDER BY next_run_at")) {
                ps.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(definition(rs));
                }
            }
            return out;
        });
    }

    /** Nuova pianificazione (validata); la prossima esecuzione si ricalcola da adesso. */
    public void reschedule(String code, String schedule, String zone) {
        ZoneId z = ZoneId.of(zone);
        Schedule s = Schedule.parse(schedule, z);
        update(code, "UPDATE eone_connect.job SET schedule = ?, zone = ?, next_run_at = ? WHERE code = ?",
                s.text(), zone, Timestamp.from(s.next(Instant.now())));
    }

    public void setEnabled(String code, boolean enabled) {
        update(code, "UPDATE eone_connect.job SET enabled = ? WHERE code = ?", enabled);
    }

    /** Esegui al prossimo giro dello scheduler (anche se la pianificazione direbbe piu' tardi). */
    public void runNow(String code) {
        update(code, "UPDATE eone_connect.job SET next_run_at = now() WHERE code = ?");
    }

    public List<JobRun> runs(String code, int limit) {
        return db.tx(c -> {
            List<JobRun> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, job_code, node, started_at, ended_at, status, message,
                           ARRAY(SELECT e.key || chr(31) || e.value FROM jsonb_each_text(counts) e) AS counts_kv
                      FROM eone_connect.job_run WHERE job_code = ? ORDER BY started_at DESC, id DESC LIMIT ?""")) {
                ps.setString(1, code);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Long> counts = new LinkedHashMap<>();
                        Json.read(rs.getArray("counts_kv")).forEach((k, v) -> counts.put(k, Long.parseLong(v)));
                        Timestamp end = rs.getTimestamp("ended_at");
                        out.add(new JobRun(rs.getLong("id"), rs.getString("job_code"), rs.getString("node"),
                                rs.getTimestamp("started_at").toInstant(), end == null ? null : end.toInstant(),
                                rs.getString("status"), rs.getString("message"), counts));
                    }
                }
            }
            return out;
        });
    }

    /** Pulizia dello storico; restituisce le righe eliminate. */
    public int purgeRuns(Instant olderThan) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM eone_connect.job_run WHERE started_at < ? AND status <> 'RUNNING'")) {
                ps.setTimestamp(1, Timestamp.from(olderThan));
                return ps.executeUpdate();
            }
        });
    }

    private void update(String code, String sql, Object... args) {
        int n = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                for (Object a : args) ps.setObject(i++, a);
                ps.setString(i, code);
                return ps.executeUpdate();
            }
        });
        if (n == 0) throw new IllegalArgumentException("Job " + code + " inesistente");
    }

    static JobDefinition definition(ResultSet rs) throws SQLException {
        Timestamp next = rs.getTimestamp("next_run_at"), idle = rs.getTimestamp("last_idle_at");
        return new JobDefinition(rs.getString("code"), rs.getString("job_key"), rs.getString("description"),
                rs.getString("schedule"), rs.getString("zone"), rs.getBoolean("enabled"),
                Json.read(rs.getArray("params_kv")), next == null ? null : next.toInstant(),
                idle == null ? null : idle.toInstant());
    }
}
