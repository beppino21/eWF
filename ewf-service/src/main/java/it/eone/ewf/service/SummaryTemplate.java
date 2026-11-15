package it.eone.ewf.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Riepilogo di una riga della lista: testo con segnaposti {chiave}, presi dagli attributi del documento
 * (es. {sap.LIFNR}, {supplier_name}) e da quelli calcolati ({documentKey}, {value}, {starter}, ...).
 * Un segnaposto senza valore sparisce; separatori rimasti orfani (" - ", ", ") e spazi doppi vengono ripuliti.
 */
final class SummaryTemplate {
    private SummaryTemplate() {}

    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    static String render(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) return null;
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v.trim()));
        }
        m.appendTail(sb);
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        // separatori senza nulla da una parte o dall'altra
        String prev;
        do {
            prev = s;
            s = s.replaceAll("^([-,/|]\\s*)+", "")
                 .replaceAll("(\\s*[-,/|])+$", "")
                 .replaceAll("([-,/|])\\s*(\\s*[-,/|])+", "$1")
                 .replaceAll("\\s+", " ").trim();
        } while (!s.equals(prev));
        return s.isEmpty() ? null : s;
    }
}
