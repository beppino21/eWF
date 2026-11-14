package it.eone.connect.mail;

import java.util.List;
import java.util.Objects;

/**
 * Una mail da spedire.
 * @param source   applicazione che la genera (ewf, ticket, ...)
 * @param ref      riferimento applicativo, per ritrovarla (facoltativo)
 * @param dedupKey chiave anti-doppione: due accodamenti con la stessa chiave producono una sola mail (facoltativa)
 * @param bodyHtml facoltativo: se presente la mail ha due versioni, testo e HTML
 */
public record MailMessage(String source, String ref, String dedupKey, List<String> to, List<String> cc,
                          String subject, String bodyText, String bodyHtml) {

    public MailMessage {
        Objects.requireNonNull(source, "source");
        to = List.copyOf(Objects.requireNonNull(to, "to"));
        cc = cc == null ? List.of() : List.copyOf(cc);
        if (to.isEmpty()) throw new IllegalArgumentException("Nessun destinatario");
        for (String a : to) checkAddress(a);
        for (String a : cc) checkAddress(a);
        Objects.requireNonNull(subject, "subject");
        if (subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0)
            throw new IllegalArgumentException("A capo non ammesso nell'oggetto");
        Objects.requireNonNull(bodyText, "bodyText");
    }

    public static MailMessage of(String source, String to, String subject, String bodyText) {
        return new MailMessage(source, null, null, List.of(to), List.of(), subject, bodyText, null);
    }

    public MailMessage withHtml(String html) {
        return new MailMessage(source, ref, dedupKey, to, cc, subject, bodyText, html);
    }

    public MailMessage withKey(String ref, String dedupKey) {
        return new MailMessage(source, ref, dedupKey, to, cc, subject, bodyText, bodyHtml);
    }

    /** Controllo minimo: niente spazi, a-capo o virgole (iniezione di intestazioni), una sola @. */
    static void checkAddress(String a) {
        if (a == null || !a.matches("[^\\s,<>@]+@[^\\s,<>@]+\\.[^\\s,<>@]+"))
            throw new IllegalArgumentException("Indirizzo mail non valido: " + a);
    }
}
