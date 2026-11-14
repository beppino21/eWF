package it.eone.connect.mail;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OAuth2ClientCredentialsTest {

    @Test
    void tokenRichiestoUnaVoltaFinoAllaScadenza() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> form = new AtomicReference<>();
        AtomicReference<Integer> status = new AtomicReference<>(200);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/token", ex -> {
            calls.incrementAndGet();
            form.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = (status.get() == 200
                    ? "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"abc.def\"}"
                    : "{\"error\":\"invalid_client\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        srv.start();
        try {
            Instant[] now = {Instant.parse("2026-11-13T10:00:00Z")};
            Clock clock = new Clock() {
                public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(java.time.ZoneId z) { return this; }
                public Instant instant() { return now[0]; }
            };
            OAuth2ClientCredentials p = new OAuth2ClientCredentials(
                    URI.create("http://127.0.0.1:" + srv.getAddress().getPort() + "/token"), "cid", () -> "s&greto",
                    "https://outlook.office365.com/.default", HttpClient.newHttpClient(), clock);
            assertEquals("abc.def", p.token());
            assertEquals("abc.def", p.token());
            assertEquals(1, calls.get(), "in memoria finche' valido");
            assertTrue(form.get().contains("grant_type=client_credentials"));
            assertTrue(form.get().contains("client_secret=s%26greto"), "parametri codificati");

            now[0] = now[0].plusSeconds(3550);                    // oltre scadenza - 60s
            p.token();
            assertEquals(2, calls.get());

            status.set(401);
            now[0] = now[0].plusSeconds(4000);
            MailException e = assertThrows(MailException.class, p::token);
            assertTrue(e.permanent(), "client o segreto sbagliati: non insistere");
        } finally {
            srv.stop(0);
        }
    }
}
