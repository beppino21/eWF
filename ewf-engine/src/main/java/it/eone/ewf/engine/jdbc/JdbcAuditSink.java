package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.spi.AuditSink;

import java.sql.PreparedStatement;

/** Audit su ewf_core.audit_event (solo INSERT; UPDATE e DELETE sono bloccati da trigger e permessi). */
public final class JdbcAuditSink implements AuditSink {

    private final JdbcTransactions db;

    public JdbcAuditSink(JdbcTransactions db) {
        this.db = db;
    }

    @Override
    public void append(AuditEvent e) {
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO ewf_core.audit_event(occurred_at, actor_kind, actor_id, process_id, task_id,
                           event_type, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""")) {
                Sql.setInstant(ps, 1, e.at());
                ps.setString(2, e.actorKind());
                Sql.setLong(ps, 3, e.actorId());
                Sql.setLong(ps, 4, e.processId());
                Sql.setLong(ps, 5, e.taskId());
                ps.setString(6, e.type());
                ps.setString(7, Sql.json(e.payload()));
                return ps.executeUpdate();
            }
        });
    }
}
