package it.eone.connect.mail;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SmtpTransportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneId.of("Europe/Rome"));

    private static SmtpConfig cfg(int port, SmtpConfig.Security sec, SmtpConfig.Auth auth) {
        return new SmtpConfig("localhost", port, sec, auth, "ewf@eone.it", () -> "segreta", () -> "TOKEN123",
                "eWF <ewf@eone.it>", "test", 5000);
    }

    private static SmtpTransport transport(SmtpConfig c) throws Exception {
        return new SmtpTransport(c, FakeSmtpServer.context().getSocketFactory(), CLOCK);
    }

    private static MailMessage mail() {
        return new MailMessage("ewf", "task 1", null, List.of("rossi@cliente.it"), List.of("capo@cliente.it"),
                "Approvazione OdA 4500001063 – 3.702,00 €", "Hai un ordine da approvare.\n.riga che inizia col punto",
                "<p>Hai un ordine da <b>approvare</b>.</p>");
    }

    /** Messaggio ricevuto dal server di prova, riletto con il parser MIME di Jakarta Mail. */
    private static jakarta.mail.internet.MimeMessage parse(String data) throws Exception {
        return new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
                new java.io.ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** Testo semplice della mail (prima parte di multipart/alternative). */
    private static String textPart(String data) throws Exception {
        Object c = parse(data).getContent();
        if (c instanceof String s) return s;
        return (String) ((jakarta.mail.internet.MimeMultipart) c).getBodyPart(0).getContent();
    }

    @Test
    void starttlsConPassword() throws Exception {
        try (FakeSmtpServer s = new FakeSmtpServer()) {
            s.offerStartTls = true;
            transport(cfg(s.port(), SmtpConfig.Security.STARTTLS, SmtpConfig.Auth.PASSWORD)).send(mail());
            FakeSmtpServer.Received r = s.received.get(0);
            assertTrue(r.tls(), "dati e credenziali solo dopo STARTTLS");
            assertEquals("PLAIN", r.authMechanism());
            // RFC 4616: [authzid] NUL authcid NUL password; l'authzid facoltativo puo' essere vuoto o uguale all'utente
            assertTrue(r.authPayload().endsWith("\0ewf@eone.it\0segreta")
                    && (r.authPayload().startsWith("\0") || r.authPayload().startsWith("ewf@eone.it\0")),
                    "credenziali PLAIN: " + r.authPayload().replace('\0', '|'));
            assertEquals("ewf@eone.it", r.from());
            assertEquals(List.of("rossi@cliente.it", "capo@cliente.it"), r.rcpt(), "anche il Cc riceve");
            jakarta.mail.internet.MimeMessage msg = parse(r.data());
            assertEquals("eWF <ewf@eone.it>", msg.getFrom()[0].toString());
            assertTrue(r.data().contains("Subject: =?UTF-8?"), "oggetto con caratteri non ASCII codificato");
            assertEquals("Approvazione OdA 4500001063 – 3.702,00 €", msg.getSubject());
            assertEquals("Hai un ordine da approvare.\n.riga che inizia col punto",
                    textPart(r.data()).replace("\r\n", "\n"), "anche la riga che inizia col punto arriva intatta");
            assertTrue(msg.getContentType().startsWith("multipart/alternative"));
        }
    }

    @Test
    void senzaStarttlsSulServerNonSiMandanoCredenzialiInChiaro() throws Exception {
        try (FakeSmtpServer s = new FakeSmtpServer()) {
            s.offerStartTls = false;
            MailException e = assertThrows(MailException.class,
                    () -> transport(cfg(s.port(), SmtpConfig.Security.STARTTLS, SmtpConfig.Auth.PASSWORD)).send(mail()));
            assertTrue(e.permanent());
            assertTrue(s.received.isEmpty());
        }
    }

    @Test
    void authLoginQuandoPlainNonCe() throws Exception {
        try (FakeSmtpServer s = new FakeSmtpServer()) {
            s.authMechanisms = "LOGIN";
            transport(cfg(s.port(), SmtpConfig.Security.NONE, SmtpConfig.Auth.PASSWORD)).send(mail());
            assertEquals("ewf@eone.it:segreta", s.received.get(0).authPayload());
        }
    }

    @Test
    void oauth2Xoauth2() throws Exception {
        try (FakeSmtpServer s = new FakeSmtpServer()) {
            s.offerStartTls = true;
            transport(cfg(s.port(), SmtpConfig.Security.STARTTLS, SmtpConfig.Auth.OAUTH2)).send(mail());
            FakeSmtpServer.Received r = s.received.get(0);
            assertEquals("XOAUTH2", r.authMechanism());
            assertEquals("user=ewf@eone.it\u0001auth=Bearer TOKEN123\u0001\u0001", r.authPayload());
        }
    }

    @Test
    void relaySenzaAutenticazione() throws Exception {
        try (FakeSmtpServer s = new FakeSmtpServer()) {
            transport(cfg(s.port(), SmtpConfig.Security.NONE, SmtpConfig.Auth.NONE)).send(mail());
            assertNull(s.received.get(0).authMechanism());
        }
    }

    @Test
    void erroriPermanentiETemporanei() throws Exception {
        try (FakeSmtpServer s = new FakeSmtpServer()) {
            SmtpTransport t = transport(cfg(s.port(), SmtpConfig.Security.NONE, SmtpConfig.Auth.PASSWORD));
            s.rejectRcpt = Set.of("rossi@cliente.it");
            MailException e1 = assertThrows(MailException.class, () -> t.send(mail()));
            assertTrue(e1.permanent(), e1.getMessage());

            s.rejectRcpt = Set.of();
            s.busyOnMail = true;
            MailException e2 = assertThrows(MailException.class, () -> t.send(mail()));
            assertFalse(e2.permanent(), "451: si ritenta");

            s.busyOnMail = false;
            s.denyAuth = true;
            MailException e3 = assertThrows(MailException.class, () -> t.send(mail()));
            assertTrue(e3.permanent());
            assertFalse(e3.getMessage().contains(Base64.getEncoder().encodeToString("\0ewf@eone.it\0segreta".getBytes())),
                    "le credenziali non finiscono nei messaggi d'errore");
        }
    }

    @Test
    void serverIrraggiungibileETemporaneo() {
        MailException e = assertThrows(MailException.class,
                () -> transport(cfg(1, SmtpConfig.Security.NONE, SmtpConfig.Auth.NONE)).send(mail()));
        assertFalse(e.permanent());
    }

    @Test
    void indirizziEIntestazioniNonValidi() {
        assertThrows(IllegalArgumentException.class, () -> MailMessage.of("ewf", "rossi@cliente.it\r\nBcc: x@y.it", "s", "b"));
        assertThrows(IllegalArgumentException.class, () -> MailMessage.of("ewf", "senza-chiocciola", "s", "b"));
        assertThrows(IllegalArgumentException.class,
                () -> MailMessage.of("ewf", "rossi@cliente.it", "oggetto\r\nBcc: x@y.it", "b"));
    }
}
