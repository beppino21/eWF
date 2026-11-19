package it.eone.ewf.service;

import it.eone.ewf.engine.EngineException;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.service.ServiceException.Code;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Funzioni di amministrazione. E' amministratore chi fa parte (oggi) del team di riserva del motore
 * (EngineConfig.fallbackTeamCode, default WFADM): lo stesso team che riceve i task senza assegnatario.
 *
 * Processi in errore (stato ERROR), due casi:
 *  - END_FAILED: l'azione di fine processo (es. rilascio dell'OdA) e' fallita -> "Riprova";
 *  - UNKNOWN_EXECUTION: una chiamata al sistema di origine ha avuto esito incerto -> l'amministratore verifica
 *    nel sistema di origine e conferma "e' avvenuta" (il processo avanza) o "non e' avvenuta" (il task si riapre).
 */
public final class AdminService {

    public enum ErrorKind { END_FAILED, UNKNOWN, OTHER }

    /** Un processo in errore, con quello che serve per decidere. */
    public record ProcessError(long processId, String processType, String processText, String docType, String documentKey,
                               String starter, ErrorKind kind, String reason, Instant since,
                               Long executionId, String handler, String event, String taskText, String message) {}

    private final WorkflowEngine engine;
    private final JdbcTransactions db;
    private final String adminTeam;
    private final Clock clock;
    private final ZoneId zone;

    public AdminService(WorkflowEngine engine, JdbcTransactions db, String adminTeam, Clock clock, ZoneId zone) {
        this.engine = engine;
        this.db = db;
        this.adminTeam = adminTeam;
        this.clock = clock;
        this.zone = zone;
    }

    public String adminTeam() { return adminTeam; }

    /** Membro oggi del team di amministrazione. */
    public boolean isAdmin(long userId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT 1 FROM ewf_org.team_member tm
                      JOIN ewf_org.app_user u ON u.id = tm.user_id AND u.active
                     WHERE tm.team_code = ? AND tm.user_id = ?
                       AND (tm.valid_from IS NULL OR tm.valid_from <= ?)
                       AND (tm.valid_to   IS NULL OR tm.valid_to   >= ?)""")) {
                ps.setString(1, adminTeam);
                ps.setLong(2, userId);
                ps.setDate(3, java.sql.Date.valueOf(today));
                ps.setDate(4, java.sql.Date.valueOf(today));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private void requireAdmin(long userId) {
        if (!isAdmin(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Funzione riservata agli amministratori");
    }

    /** Processi in errore, dal piu' recente. */
    public List<ProcessError> processesInError(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT p.id, p.process_type_code, pt.description, p.doc_type, p.document_key, su.display_name,
                           p.status_reason,
                           (SELECT max(coalesce(h.ended_at, h.started_at)) FROM ewf_core.handler_execution h
                             WHERE h.process_id = p.id) AS since,
                           e.id, e.handler_code, e.event, d.text, e.result_message
                      FROM ewf_core.process_instance p
                      JOIN ewf_core.process_type pt ON pt.code = p.process_type_code
                      LEFT JOIN ewf_org.app_user su ON su.id = p.started_by
                      LEFT JOIN LATERAL (
                            SELECT x.* FROM ewf_core.handler_execution x
                             WHERE x.process_id = p.id AND x.status = 'UNKNOWN'
                             ORDER BY x.id DESC LIMIT 1) e ON true
                      LEFT JOIN ewf_core.task_instance t ON t.id = e.task_id
                      LEFT JOIN ewf_core.task_def d ON d.id = t.task_def_id
                     WHERE p.status = 'ERROR'
                     ORDER BY since DESC NULLS LAST, p.id DESC""")) {
                List<ProcessError> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String reason = rs.getString(7);
                        Long execId = rs.getObject(9) == null ? null : rs.getLong(9);
                        ErrorKind kind = reason != null && reason.startsWith(WorkflowEngine.REASON_END_FAILED) ? ErrorKind.END_FAILED
                                : execId != null ? ErrorKind.UNKNOWN : ErrorKind.OTHER;
                        Timestamp since = rs.getTimestamp(8);
                        out.add(new ProcessError(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), kind, reason, since == null ? null : since.toInstant(),
                                execId, rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13)));
                    }
                }
                return out;
            }
        });
    }

    /** Riprova l'azione di fine processo (caso END_FAILED). Restituisce il nuovo stato del processo. */
    public String retryCompletion(long adminId, long processId) {
        requireAdmin(adminId);
        try {
            return engine.retryCompletion(processId, adminId).status.name();
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }

    /**
     * Conferma l'esito di una chiamata incerta (caso UNKNOWN) dopo averlo verificato nel sistema di origine.
     * La nota e' obbligatoria: resta nello storico della chiamata e nell'audit.
     */
    public Requests.Outcome resolveUnknown(long adminId, long executionId, boolean succeeded, String note) {
        requireAdmin(adminId);
        if (note == null || note.isBlank())
            throw new ServiceException(Code.INVALID, "Indicare cosa e' stato verificato nel sistema di origine");
        try {
            return engine.resolveUnknown(executionId, succeeded, adminId, note.trim());
        } catch (EngineException e) {
            throw ServiceException.from(e);
        }
    }
}
