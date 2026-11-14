package it.eone.connect.mail;

/** Come una mail lascia l'applicazione. Oggi SMTP; domani eventualmente Microsoft Graph o altro. */
public interface MailTransport {
    void send(MailMessage message) throws MailException;
}
