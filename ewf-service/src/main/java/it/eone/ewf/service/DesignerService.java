package it.eone.ewf.service;

import it.eone.ewf.engine.jdbc.JdbcDefinitionStore;
import it.eone.ewf.engine.jdbc.JdbcDefinitionWriter;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.service.ServiceException.Code;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
}
