package it.eone.connect.db;

import java.sql.Array;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON piatto (chiave -> valore) da/verso jsonb, senza librerie esterne. */
public final class Json {
    private Json() { }

    public static String of(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            sb.append(v instanceof Number ? v.toString() : v == null ? "null" : quote(v.toString()));
        }
        return sb.append('}').toString();
    }

    /** Colonna da selezionare: ARRAY(SELECT key || chr(31) || value FROM jsonb_each_text(col)). */
    public static String select(String column, String alias) {
        return "ARRAY(SELECT e.key || chr(31) || e.value FROM jsonb_each_text(" + column + ") e) AS " + alias;
    }

    public static Map<String, String> read(Array a) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        if (a == null) return out;
        for (Object o : (Object[]) a.getArray()) {
            String kv = (String) o;
            int i = kv.indexOf('\u001f');
            out.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return out;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
