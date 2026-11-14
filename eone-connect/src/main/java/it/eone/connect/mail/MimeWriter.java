package it.eone.connect.mail;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Compone il messaggio RFC 5322 / MIME.
 * Corpi in UTF-8 codificati base64 (righe da 76): nessun problema di lunghezza riga, di caratteri accentati
 * o di righe che iniziano con un punto. Oggetto con encoded-word se contiene caratteri non ASCII.
 */
final class MimeWriter {

    private MimeWriter() { }

    static String write(MailMessage m, String from, String domain, ZonedDateTime now) {
        StringBuilder sb = new StringBuilder();
        header(sb, "From", from);
        header(sb, "To", String.join(", ", m.to()));
        if (!m.cc().isEmpty()) header(sb, "Cc", String.join(", ", m.cc()));
        String subject = m.subject().replace('\t', ' ');
        if (subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0)
            throw new IllegalArgumentException("A capo non ammesso nell'oggetto");
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");   // puo' andare a capo (RFC 2047)
        header(sb, "Date", DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH).format(now));
        header(sb, "Message-ID", "<" + UUID.randomUUID() + "@" + domain + ">");
        header(sb, "MIME-Version", "1.0");
        if (m.bodyHtml() == null) {
            header(sb, "Content-Type", "text/plain; charset=UTF-8");
            header(sb, "Content-Transfer-Encoding", "base64");
            sb.append("\r\n").append(base64(m.bodyText()));
        } else {
            String boundary = "=_eone_" + UUID.randomUUID().toString().replace("-", "");
            header(sb, "Content-Type", "multipart/alternative; boundary=\"" + boundary + "\"");
            sb.append("\r\n");
            part(sb, boundary, "text/plain", m.bodyText());
            part(sb, boundary, "text/html", m.bodyHtml());
            sb.append("--").append(boundary).append("--\r\n");
        }
        return sb.toString();
    }

    private static void part(StringBuilder sb, String boundary, String type, String body) {
        sb.append("--").append(boundary).append("\r\n");
        header(sb, "Content-Type", type + "; charset=UTF-8");
        header(sb, "Content-Transfer-Encoding", "base64");
        sb.append("\r\n").append(base64(body));
    }

    private static void header(StringBuilder sb, String name, String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IllegalArgumentException("A capo non ammesso nell'intestazione " + name);
        sb.append(name).append(": ").append(value).append("\r\n");
    }

    /** RFC 2047: =?UTF-8?B?...?= a pezzi, cosi' nessuna parola codificata supera i 75 caratteri. */
    static String encodeHeader(String s) {
        if (s.chars().allMatch(ch -> ch >= 32 && ch < 127)) return s;
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            String c = new String(Character.toChars(cp));
            int n = c.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + n > 45) {                       // 45 byte -> 60 caratteri base64
                out.append(out.isEmpty() ? "" : "\r\n ").append(word(chunk.toString()));
                chunk.setLength(0);
                bytes = 0;
            }
            chunk.append(c);
            bytes += n;
            i += Character.charCount(cp);
        }
        out.append(out.isEmpty() ? "" : "\r\n ").append(word(chunk.toString()));
        return out.toString();
    }

    private static String word(String s) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String base64(String body) {
        return Base64.getMimeEncoder(76, new byte[]{'\r', '\n'})
                .encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "\r\n";
    }
}
