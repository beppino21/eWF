package it.eone.connect.mail;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server SMTP minimo per i test: accetta connessioni in sequenza e registra quello che riceve. */
final class FakeSmtpServer implements AutoCloseable {

    record Received(String from, List<String> rcpt, String data, String authMechanism, String authPayload,
                    boolean tls) {}

    final List<Received> received = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    volatile boolean offerStartTls;
    volatile String authMechanisms = "PLAIN LOGIN XOAUTH2";
    volatile Set<String> rejectRcpt = Set.of();
    volatile boolean busyOnMail;          // 451 su MAIL FROM
    volatile boolean denyAuth;            // 535 su AUTH

    private final ServerSocket server;
    private final Thread thread;

    FakeSmtpServer() throws Exception {
        server = new ServerSocket(0);
        thread = new Thread(this::loop, "fake-smtp");
        thread.setDaemon(true);
        thread.start();
    }

    int port() { return server.getLocalPort(); }

    static SSLContext context() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = FakeSmtpServer.class.getResourceAsStream("smtp-test.p12")) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private void loop() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                handle(s);
            } catch (Exception e) {
                if (!server.isClosed()) errors.add(e.toString());
            }
        }
    }

    private void handle(Socket socket) throws Exception {
        Socket s = socket;
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = s.getOutputStream();
        boolean tls = false;
        String from = null, mech = null, payload = null;
        List<String> rcpt = new CopyOnWriteArrayList<>();
        send(out, "220 fake ESMTP");
        String line;
        while ((line = in.readLine()) != null) {
            String up = line.toUpperCase();
            if (up.startsWith("EHLO")) {
                send(out, "250-fake");
                if (offerStartTls && !tls) send(out, "250-STARTTLS");
                send(out, "250-AUTH " + authMechanisms);
                send(out, "250 8BITMIME");
            } else if (up.equals("STARTTLS")) {
                send(out, "220 go ahead");
                SSLSocket t = (SSLSocket) context().getSocketFactory().createSocket(s, null, s.getPort(), true);
                t.setUseClientMode(false);
                t.startHandshake();
                s = t;
                tls = true;
                in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out = s.getOutputStream();
            } else if (up.startsWith("AUTH LOGIN")) {
                send(out, "334 VXNlcm5hbWU6");
                String u = new String(Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                send(out, "334 UGFzc3dvcmQ6");
                String p = new String(Base64.getDecoder().decode(in.readLine()), StandardCharsets.UTF_8);
                mech = "LOGIN";
                payload = u + ":" + p;
                send(out, denyAuth ? "535 5.7.8 credenziali non valide" : "235 ok");
            } else if (up.startsWith("AUTH ")) {
                String[] parts = line.split(" ", 3);
                mech = parts[1].toUpperCase();
                payload = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                send(out, denyAuth ? "535 5.7.8 credenziali non valide" : "235 ok");
            } else if (up.startsWith("MAIL FROM:")) {
                if (busyOnMail) { send(out, "451 4.3.2 occupato, riprovare"); continue; }
                from = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                send(out, "250 ok");
            } else if (up.startsWith("RCPT TO:")) {
                String r = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                if (rejectRcpt.contains(r)) send(out, "550 5.1.1 utente sconosciuto");
                else { rcpt.add(r); send(out, "250 ok"); }
            } else if (up.equals("DATA")) {
                send(out, "354 avanti");
                StringBuilder data = new StringBuilder();
                String d;
                while (!(d = in.readLine()).equals(".")) {
                    if (d.startsWith("..")) d = d.substring(1);
                    data.append(d).append("\r\n");
                }
                received.add(new Received(from, List.copyOf(rcpt), data.toString(), mech, payload, tls));
                send(out, "250 accodata");
            } else if (up.equals("QUIT")) {
                send(out, "221 ciao");
                return;
            } else {
                send(out, "502 comando non riconosciuto");
            }
        }
    }

    private static void send(OutputStream out, String line) throws Exception {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() throws Exception {
        server.close();
    }
}
