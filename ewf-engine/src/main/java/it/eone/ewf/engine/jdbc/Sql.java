package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.model.Money;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Conversioni tra tipi Java e JDBC usate dagli store. */
final class Sql {
    private Sql() {}

    static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT); else ps.setLong(i, v);
    }

    static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER); else ps.setInt(i, v);
    }

    static void setInstant(PreparedStatement ps, int i, Instant v) throws SQLException {
        if (v == null) ps.setNull(i, Types.TIMESTAMP_WITH_TIMEZONE);
        else ps.setObject(i, OffsetDateTime.ofInstant(v, ZoneOffset.UTC));
    }

    static void setDate(PreparedStatement ps, int i, LocalDate v) throws SQLException {
        if (v == null) ps.setNull(i, Types.DATE); else ps.setObject(i, v);
    }

    static void setBigDecimal(PreparedStatement ps, int i, BigDecimal v) throws SQLException {
        if (v == null) ps.setNull(i, Types.NUMERIC); else ps.setBigDecimal(i, v);
    }

    static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static Integer getInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static Instant getInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    static LocalDate getDate(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    static UUID getUuid(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }

    static Money getMoney(ResultSet rs, String amountCol, String currencyCol) throws SQLException {
        BigDecimal a = rs.getBigDecimal(amountCol);
        String c = rs.getString(currencyCol);
        return a == null || c == null ? null : new Money(a, c);
    }

    /** JSON minimo per mappe di stringhe (payload audit, config). */
    static String json(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(e.getValue() == null ? "null" : quote(e.getValue()));
        }
        return sb.append('}').toString();
    }

    static String jsonArray(String... values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values[i]));
        }
        return sb.append(']').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }
}
