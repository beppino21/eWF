package it.eone.ewf.notify;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/** Un task aperto con i dati del suo processo, come compare nelle mail. */
record TaskRow(long taskId, String status, Long assigneeId, String teamCode, int stepNo, String taskText,
               String docType, String documentKey, String processType, String processName, BigDecimal value,
               String currency, String supplier, Instant createdAt, String forUser) {

    /** Colonne per le query: SELECT ROW_COLUMNS FROM ROW_FROM WHERE ... */
    static final String COLUMNS = """
            t.id, t.status, t.assignee_id, t.team_code, t.step_no, d.text, p.doc_type, p.document_key,
            p.process_type_code, pt.description, p.net_value, p.currency, p.doc_context->>'sap.LIFNR', t.created_at""";
    static final String FROM = """
            ewf_core.task_instance t
              JOIN ewf_core.task_def d ON d.id = t.task_def_id
              JOIN ewf_core.process_instance p ON p.id = t.process_id
              JOIN ewf_core.process_type pt ON pt.code = p.process_type_code""";

    static TaskRow of(ResultSet rs, int i, String forUser) throws SQLException {
        long assignee = rs.getLong(i + 2);
        Long a = rs.wasNull() ? null : assignee;
        return new TaskRow(rs.getLong(i), rs.getString(i + 1), a, rs.getString(i + 3), rs.getInt(i + 4),
                rs.getString(i + 5), rs.getString(i + 6), rs.getString(i + 7), rs.getString(i + 8), rs.getString(i + 9),
                rs.getBigDecimal(i + 10), rs.getString(i + 11), rs.getString(i + 12),
                rs.getTimestamp(i + 13).toInstant(), forUser);
    }

    String document() {
        String label = switch (docType) {
            case "F" -> "OdA";
            case "B" -> "RdA";
            case "K" -> "Contratto";
            default -> docType;
        };
        return label + " " + documentKey;
    }

    String valueText() {
        if (value == null) return "-";
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ITALY));
        return f.format(value) + (currency == null ? "" : " " + currency);
    }

    String supplierText() {
        return supplier == null || supplier.isBlank() ? "-" : supplier.replaceFirst("^0+(?=.)", "");
    }

    long waitingDays(LocalDate today, ZoneId zone) {
        return ChronoUnit.DAYS.between(createdAt.atZone(zone).toLocalDate(), today);
    }

    Map<String, String> vars() {
        return Map.of("task", taskText, "process", processName, "document", document(), "value", valueText(),
                "supplier", supplierText(), "step", String.valueOf(stepNo));
    }
}
