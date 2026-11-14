package it.eone.ewf.notify;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modelli delle mail (ewf_core.mail_template), modificabili senza toccare il codice.
 * Segnaposto {{nome}}. Nel corpo HTML i valori vengono resi sicuri (escape), tranne quelli il cui nome finisce
 * con "Html" (gia' composti in HTML da eWF). Senza modello nel DB si usa il testo predefinito qui sotto.
 */
public final class MailTemplates {

    public record Template(String subject, String bodyText, String bodyHtml) {}

    public record Rendered(String subject, String bodyText, String bodyHtml) {}

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)}}");

    private final DataSource ds;

    public MailTemplates(DataSource ds) { this.ds = ds; }

    public Template load(String code, String lang) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT subject, body_text, body_html FROM ewf_core.mail_template WHERE code = ? AND lang = ?")) {
            ps.setString(1, code);
            ps.setString(2, lang);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Template(rs.getString(1), rs.getString(2), rs.getString(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lettura modello " + code + ": " + e.getMessage(), e);
        }
        Template t = DEFAULTS.get(code);
        if (t == null) throw new IllegalStateException("Modello mail " + code + " inesistente");
        return t;
    }

    public static Rendered render(Template t, Map<String, String> vars) {
        return new Rendered(fill(t.subject(), vars, false).replaceAll("[\\r\\n]+", " ").trim(),
                fill(t.bodyText(), vars, false).replaceAll("\n{3,}", "\n\n"),   // segnaposto vuoti: niente buchi
                t.bodyHtml() == null ? null : fill(t.bodyHtml(), vars, true));
    }

    private static String fill(String s, Map<String, String> vars, boolean html) {
        Matcher m = VAR.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String v = vars.getOrDefault(name, "");
            if (html && !name.endsWith("Html")) v = escape(v);
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Uguali a quelli inseriti dalla migrazione 0.13: valgono se la tabella non ha il modello. */
    static final Map<String, Template> DEFAULTS = Map.of(
            "TASK_ACTIVATED", new Template("eWF: {{task}} - {{document}}",
                    "Gentile {{user}},\n\nti e' stata assegnata un'attivita' da svolgere:\n\n"
                            + "  {{task}}\n  Processo:   {{process}}\n  Documento:  {{document}}\n  Valore:     {{value}}\n"
                            + "  Fornitore:  {{supplier}}\n\n{{link}}\n\nQuesta e' una comunicazione automatica di eWF.",
                    "<p>Gentile {{user}},</p><p>ti &egrave; stata assegnata un'attivit&agrave; da svolgere:</p><table style=\"border-collapse:collapse\"><tr><td colspan=\"2\" style=\"padding:4px 0\"><b>{{task}}</b></td></tr><tr><td style=\"padding:2px 12px 2px 0\">Processo</td><td>{{process}}</td></tr><tr><td style=\"padding:2px 12px 2px 0\">Documento</td><td>{{document}}</td></tr><tr><td style=\"padding:2px 12px 2px 0\">Valore</td><td>{{value}}</td></tr><tr><td style=\"padding:2px 12px 2px 0\">Fornitore</td><td>{{supplier}}</td></tr></table><p>{{linkHtml}}</p><p style=\"color:#777;font-size:smaller\">Comunicazione automatica di eWF.</p>"),
            "DIGEST", new Template("eWF: {{count}} attivita' in attesa",
                    "Gentile {{user}},\n\nquesta e' la situazione delle attivita' a tuo carico:\n\n{{rows}}\n{{teamRows}}\n"
                            + "{{link}}\n\nRiceverai il prossimo riepilogo tra {{everyDays}}, se ci saranno ancora "
                            + "attivita' in attesa.",
                    "<p>Gentile {{user}},</p><p>questa &egrave; la situazione delle attivit&agrave; a tuo carico:</p>{{rowsHtml}}{{teamRowsHtml}}<p>{{linkHtml}}</p><p style=\"color:#777;font-size:smaller\">Riceverai il prossimo riepilogo tra {{everyDays}}, se ci saranno ancora attivit&agrave; in attesa.</p>"));
}
