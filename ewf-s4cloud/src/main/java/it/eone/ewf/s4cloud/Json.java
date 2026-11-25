package it.eone.ewf.s4cloud;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lettore JSON minimo per le risposte OData (nessuna dipendenza esterna).
 * Oggetti -> Map (ordine mantenuto), array -> List, numeri -> BigDecimal (gli importi non perdono precisione),
 * stringhe, Boolean, null.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.error("testo dopo la fine del JSON");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON: atteso un oggetto");
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (i >= s.length()) throw error("fine inattesa");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> obj();
            case '[' -> arr();
            case '"' -> str();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> num();
        };
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw error("attesa una chiave");
            String k = str();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw error("attesa ',' o '}'");
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw error("attesa ',' o ']'");
        }
    }

    private String str() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("\\u incompleto");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw error("escape non valido \\" + e);
            }
        }
    }

    private BigDecimal num() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("valore non valido");
        return new BigDecimal(s.substring(start, i));
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("atteso " + w);
        i += w.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private char peek() {
        if (i >= s.length()) throw error("fine inattesa");
        return s.charAt(i);
    }

    private char next() {
        char c = peek();
        i++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw error("atteso '" + c + "'");
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON non valido alla posizione " + i + ": " + msg);
    }
}
