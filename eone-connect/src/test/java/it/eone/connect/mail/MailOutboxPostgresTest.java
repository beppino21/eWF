package it.eone.connect.mail;

import it.eone.connect.db.ConnectSchema;
import it.eone.connect.db.Db;
import it.eone.connect.db.SimpleDataSource;
import it.eone.connect.scheduler.JobContext;
import it.eone.connect.scheduler.JobResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MailOutboxPostgresTest {

    private Db db;
    private MailOutbox outbox;
    // parte dall'ora vera: l'accodamento usa now() del DB
    private final Instant[] now = {Instant.now().plusSeconds(1)};
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now[0]; }
    };

    @BeforeEach
    void setUp() {
        String url = System.getenv("CONNECT_TEST_DB_URL");
        Assumptions.assumeTrue(url != null, "CONNECT_TEST_DB_URL non impostata");
        db = new Db(new SimpleDataSource(url, System.getenv("CONNECT_TEST_DB_USER"), System.getenv("CONNECT_TEST_DB_PASSWORD")));
        db.tx(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS eone_connect CASCADE");
            }
            return null;
        });
        ConnectSchema.migrate(db, null);
        outbox = new MailOutbox(db);
    }

    private static MailMessage mail(String key) {
        return MailMessage.of("ewf", "rossi@cliente.it", "Oggetto " + key, "corpo").withKey("ref-" + key, key);
    }

    private JobResult runJob(MailTransport t) {
        return new MailSendJob(outbox, t, clock).run(new JobContext("EONE_MAIL_SEND", 1L, "A", Map.of(), () -> false));
    }

    @Test
    void stessaChiaveUnaSolaMailEAccodamentoNellaTransazioneDelChiamante() {
        assertTrue(outbox.enqueue(mail("task-1")).isPresent());
        assertTrue(outbox.enqueue(mail("task-1")).isEmpty(), "ripetere e' sicuro");
        assertThrows(RuntimeException.class, () -> db.tx(c -> {
            MailOutbox.enqueue(c, mail("task-2"));
            throw new IllegalStateException("l'avvio del processo fallisce");
        }));
        assertEquals(1, outbox.recent("ewf", 10).size(), "rollback del chiamante: niente mail");
    }

    @Test
    void spedizioneRitentativiEScarto() {
        outbox.enqueue(mail("a"));
        outbox.enqueue(mail("b"));
        outbox.enqueue(mail("c"));
        List<String> sent = new ArrayList<>();
        int[] busy = {1};
        JobResult r = runJob(m -> {
            if (m.dedupKey().equals("b") && busy[0]-- > 0) throw new MailException("451 occupato", false);
            if (m.dedupKey().equals("c")) throw new MailException("550 utente sconosciuto", true);
            sent.add(m.dedupKey());
        });
        assertEquals(JobResult.Status.ERROR, r.status(), r.message());
        assertEquals(Map.of("spedite", 1L, "rimandate", 1L, "scartate", 1L, "cancellate", 0L), r.counts());
        assertEquals(List.of("a"), sent);

        assertEquals(JobResult.Status.IDLE, runJob(m -> sent.add(m.dedupKey())).status(), "b non ancora dovuta");
        now[0] = now[0].plus(Duration.ofMinutes(2));
        assertEquals(JobResult.Status.OK, runJob(m -> sent.add(m.dedupKey())).status());
        assertEquals(List.of("a", "b"), sent, "b ritentata dopo l'attesa; c resta DEAD");

        MailOutbox.Row c = outbox.recent("ewf", 10).stream().filter(x -> x.ref().equals("ref-c")).findFirst().orElseThrow();
        assertEquals("DEAD", c.status());
        assertTrue(c.lastError().contains("550"));
        assertTrue(outbox.retry(c.id()), "dopo aver sistemato si puo' rimettere in coda");
        runJob(m -> sent.add(m.dedupKey()));
        assertEquals(List.of("a", "b", "c"), sent);
    }

    @Test
    void tentativiEsauritiDiventaDead() {
        outbox.enqueue(mail("x"));
        for (int i = 0; i <= MailOutbox.BACKOFF.size(); i++) {
            runJob(m -> { throw new MailException("timeout", false); });
            now[0] = now[0].plus(Duration.ofHours(13));
        }
        assertEquals("DEAD", outbox.recent("ewf", 1).get(0).status());
        assertEquals(MailOutbox.BACKOFF.size() + 1, outbox.recent("ewf", 1).get(0).attempts());
    }

    @Test
    void invioRimastoAppesoVieneRipresoDopoQuindiciMinuti() {
        outbox.enqueue(mail("y"));
        assertEquals(1, outbox.claim(10, now[0]).size());        // l'istanza cade qui, senza esito
        assertEquals(0, outbox.claim(10, now[0]).size(), "intanto nessun altro la prende");
        now[0] = now[0].plus(Duration.ofMinutes(16));
        assertEquals(1, outbox.claim(10, now[0]).size());
    }

    @Test
    void speditePiuVecchieDelPeriodoVengonoCancellate() {
        outbox.enqueue(mail("z"));
        runJob(m -> { });
        now[0] = now[0].plus(Duration.ofDays(31));
        outbox.enqueue(mail("w"));
        JobResult r = runJob(m -> { });
        assertEquals(1L, r.counts().get("cancellate"));
        assertEquals(1, outbox.recent("ewf", 10).size());
    }
}
