package it.eone.connect.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.time.Clock;
import java.util.Date;
import java.util.Properties;

/**
 * Invio SMTP con Jakarta Mail (implementazione Angus Mail): STARTTLS o SSL, AUTH PLAIN/LOGIN, OAuth2 (XOAUTH2).
 *
 * Errori: autenticazione rifiutata, destinatario rifiutato (5xx), STARTTLS richiesto ma non offerto -> permanenti;
 * rete, timeout, 4xx -> temporanei (la mail verra' ritentata). Jakarta Mail non mette le credenziali nei messaggi.
 *
 * socketFactory (facoltativa): per aprire la connessione in modo particolare, es. su SAP BTP Cloud Foundry verso un
 * server di posta on-premise attraverso il servizio di connettivita' e il Cloud Connector (SOCKS5).
 */
public final class SmtpTransport implements MailTransport {

    private final SmtpConfig cfg;
    private final SSLSocketFactory ssl;
    private final SocketFactory socketFactory;
    private final Clock clock;

    public SmtpTransport(SmtpConfig cfg) { this(cfg, null, null, Clock.systemDefaultZone()); }

    /** ssl: fabbrica TLS (nei test, una che si fida del certificato del server di prova); null = quella della JVM. */
    public SmtpTransport(SmtpConfig cfg, SSLSocketFactory ssl, Clock clock) { this(cfg, ssl, null, clock); }

    public SmtpTransport(SmtpConfig cfg, SSLSocketFactory ssl, SocketFactory socketFactory, Clock clock) {
        this.cfg = cfg;
        this.ssl = ssl;
        this.socketFactory = socketFactory;
        this.clock = clock;
    }

    @Override
    public void send(MailMessage m) throws MailException {
        String proto = cfg.security() == SmtpConfig.Security.SSL ? "smtps" : "smtp";
        Session session = Session.getInstance(properties(proto));
        try {
            MimeMessage msg = message(session, m);
            String user = null, secret = null;
            switch (cfg.auth()) {
                case NONE -> { }
                case PASSWORD -> { user = cfg.username(); secret = cfg.password().get(); }
                case OAUTH2 -> { user = cfg.username(); secret = cfg.tokens().token(); }
            }
            try (Transport t = session.getTransport(proto)) {
                t.connect(cfg.host(), cfg.port(), user, secret);
                t.sendMessage(msg, msg.getAllRecipients());
            }
        } catch (MessagingException e) {
            throw new MailException("SMTP " + cfg.host() + ":" + cfg.port() + " - " + describe(e), permanent(e), e);
        }
    }

    private Properties properties(String proto) {
        String p = "mail." + proto + ".";
        Properties props = new Properties();
        props.put("mail.transport.protocol", proto);
        props.put(p + "host", cfg.host());
        props.put(p + "port", String.valueOf(cfg.port()));
        props.put(p + "localhost", cfg.heloName());
        props.put(p + "connectiontimeout", String.valueOf(cfg.timeoutMillis()));
        props.put(p + "timeout", String.valueOf(cfg.timeoutMillis()));
        props.put(p + "writetimeout", String.valueOf(cfg.timeoutMillis()));
        props.put("mail.mime.charset", "UTF-8");
        if (cfg.security() == SmtpConfig.Security.STARTTLS) {
            props.put(p + "starttls.enable", "true");
            props.put(p + "starttls.required", "true");        // mai credenziali o dati in chiaro
        }
        if (cfg.security() != SmtpConfig.Security.NONE) {
            props.put(p + "ssl.checkserveridentity", "true");  // il certificato deve essere del server
            if (ssl != null) props.put(p + "ssl.socketFactory", ssl);
        }
        if (socketFactory != null) props.put(p + "socketFactory", socketFactory);
        switch (cfg.auth()) {
            case NONE -> props.put(p + "auth", "false");
            case PASSWORD -> {
                props.put(p + "auth", "true");
                props.put(p + "auth.mechanisms", "PLAIN LOGIN");
            }
            case OAUTH2 -> {
                props.put(p + "auth", "true");
                props.put(p + "auth.mechanisms", "XOAUTH2");
            }
        }
        return props;
    }

    private MimeMessage message(Session session, MailMessage m) throws MessagingException {
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(cfg.from(), true));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(",", m.to()), true));
        if (!m.cc().isEmpty())
            msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(String.join(",", m.cc()), true));
        msg.setSubject(m.subject(), "UTF-8");
        msg.setSentDate(Date.from(clock.instant()));
        if (m.bodyHtml() == null) {
            msg.setText(m.bodyText(), "UTF-8");
        } else {
            MimeBodyPart text = new MimeBodyPart();
            text.setText(m.bodyText(), "UTF-8");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(m.bodyHtml(), "UTF-8", "html");
            MimeMultipart alt = new MimeMultipart("alternative");
            alt.addBodyPart(text);
            alt.addBodyPart(html);
            msg.setContent(alt);
        }
        msg.saveChanges();
        return msg;
    }

    /** Errore permanente (inutile ritentare) o temporaneo. */
    static boolean permanent(MessagingException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AuthenticationFailedException) return true;
            int code = returnCode(t);
            if (code >= 500) return true;
            if (code >= 400) return false;
            if (t instanceof IOException) return false;
            String msg = t.getMessage();
            if (msg != null && msg.contains("STARTTLS")) return true;      // richiesto ma non offerto dal server
            if (t instanceof SendFailedException sf && sf.getInvalidAddresses() != null
                    && sf.getInvalidAddresses().length > 0) return true;
            if (t instanceof MessagingException me && me.getNextException() != null && me.getNextException() != t) {
                Throwable n = me.getNextException();
                if (n instanceof MessagingException nm) return permanent(nm);
                if (n instanceof IOException) return false;
            }
        }
        return false;
    }

    /** Codice SMTP delle eccezioni di Angus Mail (SMTPSendFailedException, SMTPAddressFailedException), se c'e'. */
    private static int returnCode(Throwable t) {
        try {
            Object v = t.getClass().getMethod("getReturnCode").invoke(t);
            return v instanceof Integer i ? i : -1;
        } catch (ReflectiveOperationException ex) {
            return -1;
        }
    }

    private static String describe(MessagingException e) {
        StringBuilder b = new StringBuilder(String.valueOf(e.getMessage()));
        Exception next = e.getNextException();
        if (next != null && next != e && next.getMessage() != null && !b.toString().contains(next.getMessage()))
            b.append(" (").append(next.getMessage()).append(")");
        return b.toString().replaceAll("[\\r\\n]+", " ").trim();
    }
}
