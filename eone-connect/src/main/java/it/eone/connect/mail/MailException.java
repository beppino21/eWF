package it.eone.connect.mail;

/**
 * Invio non riuscito.
 * permanent = true: inutile ritentare (es. 550 destinatario inesistente, autenticazione rifiutata);
 * false: problema temporaneo (rete, server occupato, 4xx), si ritenta piu' tardi.
 */
public class MailException extends Exception {
    private static final long serialVersionUID = 1L;
    private final boolean permanent;

    public MailException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    public MailException(String message, boolean permanent) { this(message, permanent, null); }

    public boolean permanent() { return permanent; }
}
