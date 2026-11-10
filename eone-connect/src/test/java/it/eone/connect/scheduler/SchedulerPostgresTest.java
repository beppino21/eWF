package it.eone.connect.scheduler;

import it.eone.connect.db.ConnectSchema;
import it.eone.connect.db.Db;
import it.eone.connect.db.SimpleDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Su PostgreSQL vero (i lock sono di PostgreSQL). DB dedicato: lo schema eone_connect viene ricreato.
 * Variabili: CONNECT_TEST_DB_URL, CONNECT_TEST_DB_USER, CONNECT_TEST_DB_PASSWORD.
 */
class SchedulerPostgresTest {

    private Db db;

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
    }

    private Scheduler scheduler(String node, JobRegistry reg) {
        return new Scheduler(db, reg, Scheduler.Options.defaults().withNode(node).withTick(Duration.ofMillis(100)));
    }

    @Test
    void senzaSchemaLoSchedulerNonParteEDiceCosaFare() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> scheduler("A", new JobRegistry()).start());
        assertTrue(e.getMessage().contains("ConnectSchemaTool"), e.getMessage());
        assertEquals(1, ConnectSchema.migrate(db, null));
        assertEquals(1, ConnectSchema.migrate(db, null), "idempotente");
        assertEquals(1, ConnectSchema.installedVersion(db));
    }

    @Test
    void eseguitoRegistratoEProssimoGiroFissato() {
        ConnectSchema.migrate(db, null);
        AtomicInteger calls = new AtomicInteger();
        JobRegistry reg = new JobRegistry().register("t.count", ctx -> {
            calls.incrementAndGet();
            assertEquals("valore", ctx.param("chiave", null));
            return JobResult.ok("fatto").with("righe", 42);
        });
        Scheduler a = scheduler("A", reg);
        a.store().ensure("CONTA", "t.count", "Conta", "every 1h", "Europe/Rome", true, Map.of("chiave", "valore"));
        a.store().runNow("CONTA");
        a.execute("CONTA");
        assertEquals(1, calls.get());
        JobRun r = a.store().runs("CONTA", 10).get(0);
        assertEquals("OK", r.status());
        assertEquals("A", r.node());
        assertEquals("fatto", r.message());
        assertEquals(Map.of("righe", 42L), r.counts());
        Instant next = a.store().definition("CONTA").orElseThrow().nextRunAt();
        assertTrue(next.isAfter(Instant.now().plus(Duration.ofMinutes(59))), "prossimo giro tra un'ora: " + next);

        a.execute("CONTA");
        assertEquals(1, calls.get(), "non ancora scaduto: non riparte");
    }

    @Test
    void dueIstanzeNelloStessoIstanteUnaSolaEsecuzione() throws Exception {
        ConnectSchema.migrate(db, null);
        AtomicInteger concurrent = new AtomicInteger(), maxConcurrent = new AtomicInteger(), calls = new AtomicInteger();
        Job slow = ctx -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            calls.incrementAndGet();
            Thread.sleep(400);
            concurrent.decrementAndGet();
            return JobResult.ok(ctx.node());
        };
        Scheduler a = scheduler("A", new JobRegistry().register("t.slow", slow));
        Scheduler b = scheduler("B", new JobRegistry().register("t.slow", slow));
        a.store().ensure("LENTO", "t.slow", null, "every 1h", "Europe/Rome", true, Map.of());
        a.store().runNow("LENTO");

        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService ex = Executors.newFixedThreadPool(2);
        Future<?> fa = ex.submit(() -> { together.await(); a.execute("LENTO"); return null; });
        Future<?> fb = ex.submit(() -> { together.await(); b.execute("LENTO"); return null; });
        fa.get(10, TimeUnit.SECONDS);
        fb.get(10, TimeUnit.SECONDS);
        ex.shutdown();

        assertEquals(1, calls.get(), "una sola esecuzione nel cluster");
        assertEquals(1, maxConcurrent.get());
        assertEquals(1, a.store().runs("LENTO", 10).size());
    }

    @Test
    void dueIstanzeAccesePerQualcheSecondoMaiSovrapposte() throws Exception {
        ConnectSchema.migrate(db, null);
        AtomicInteger concurrent = new AtomicInteger(), maxConcurrent = new AtomicInteger();
        Job job = ctx -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            Thread.sleep(300);
            concurrent.decrementAndGet();
            return JobResult.ok(null);
        };
        Scheduler a = scheduler("A", new JobRegistry().register("t.job", job));
        Scheduler b = scheduler("B", new JobRegistry().register("t.job", job));
        a.store().ensure("OGNI_SECONDO", "t.job", null, "every 1s", "Europe/Rome", true, Map.of());
        a.store().runNow("OGNI_SECONDO");
        a.start();
        b.start();
        try {
            Thread.sleep(3600);
        } finally {
            a.stop();
            b.stop();
        }
        List<JobRun> runs = a.store().runs("OGNI_SECONDO", 50);
        assertEquals(1, maxConcurrent.get(), "mai due esecuzioni insieme");
        assertTrue(runs.size() >= 3 && runs.size() <= 5, "circa una al secondo: " + runs.size());
        assertTrue(runs.stream().allMatch(r -> r.status().equals("OK")), runs.toString());
    }

    @Test
    void erroreRegistratoEIlJobContinuaAGirare() {
        ConnectSchema.migrate(db, null);
        Scheduler a = scheduler("A", new JobRegistry().register("t.boom", ctx -> {
            throw new IllegalStateException("SAP non raggiungibile");
        }));
        a.store().ensure("ROTTO", "t.boom", null, "every 1h", "Europe/Rome", true, Map.of());
        a.store().runNow("ROTTO");
        a.execute("ROTTO");
        JobRun r = a.store().runs("ROTTO", 1).get(0);
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("SAP non raggiungibile"), r.message());
        assertNotNull(a.store().definition("ROTTO").orElseThrow().nextRunAt(), "il prossimo giro resta pianificato");
    }

    @Test
    void chiaveNonRegistrataInQuestaApplicazione() {
        ConnectSchema.migrate(db, null);
        Scheduler a = scheduler("A", new JobRegistry());
        a.store().ensure("ORFANO", "non.esiste", null, "every 1h", "Europe/Rome", true, Map.of());
        a.store().runNow("ORFANO");
        a.execute("ORFANO");
        JobRun r = a.store().runs("ORFANO", 1).get(0);
        assertEquals("ERROR", r.status());
        assertTrue(r.message().contains("non.esiste"), r.message());
    }

    @Test
    void esecuzioneRimastaAppesaDaUnIstanzaMortaVieneChiusa() {
        ConnectSchema.migrate(db, null);
        Scheduler a = scheduler("A", new JobRegistry().register("t.ok", ctx -> JobResult.ok(null)));
        a.store().ensure("RIPRESO", "t.ok", null, "every 1h", "Europe/Rome", true, Map.of());
        db.tx(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO eone_connect.job_run(job_code, node, started_at, status) "
                        + "VALUES ('RIPRESO', 'MORTO', now() - interval '1 hour', 'RUNNING')");
            }
            return null;
        });
        a.store().runNow("RIPRESO");
        a.execute("RIPRESO");
        List<JobRun> runs = a.store().runs("RIPRESO", 10);
        assertEquals("OK", runs.get(0).status());
        assertEquals("ABORTED", runs.get(1).status());
        assertEquals("MORTO", runs.get(1).node());
    }

    @Test
    void lungoInterrottoAlloSpegnimento() throws Exception {
        ConnectSchema.migrate(db, null);
        CountDownLatch started = new CountDownLatch(1);
        Scheduler a = new Scheduler(db, new JobRegistry().register("t.long", ctx -> {
            started.countDown();
            Thread.sleep(10_000);
            return JobResult.ok(null);
        }), new Scheduler.Options("A", Duration.ofMillis(100), 2, Duration.ofMillis(200), java.time.Clock.systemUTC()));
        a.store().ensure("LUNGO", "t.long", null, "every 1h", "Europe/Rome", true, Map.of());
        a.store().runNow("LUNGO");
        a.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        a.stop();
        Thread.sleep(300);
        assertEquals("ABORTED", a.store().runs("LUNGO", 1).get(0).status());
    }

    @Test
    void pianificazioneSistemaEModificabileSenzaRiavvio() {
        ConnectSchema.migrate(db, null);
        Scheduler a = scheduler("A", new JobRegistry());
        a.start();
        a.stop();
        JobDefinition purge = a.store().definition(Scheduler.PURGE_JOB).orElseThrow();
        assertEquals("daily 03:17", purge.schedule());
        a.store().reschedule(Scheduler.PURGE_JOB, "daily 02:00 sun", "Europe/Rome");
        a.start();
        a.stop();
        assertEquals("daily 02:00 sun", a.store().definition(Scheduler.PURGE_JOB).orElseThrow().schedule(),
                "ensure non sovrascrive le modifiche dell'amministratore");
        assertThrows(IllegalArgumentException.class, () -> a.store().reschedule(Scheduler.PURGE_JOB, "ogni tanto", "Europe/Rome"));
    }

    @Test
    void giroAVuotoNonEntraNelloStorico() {
        ConnectSchema.migrate(db, null);
        AtomicInteger pending = new AtomicInteger(0);
        Scheduler a = scheduler("A", new JobRegistry().register("t.coda", ctx ->
                pending.get() == 0 ? JobResult.idle() : JobResult.ok(null).with("elaborati", pending.getAndSet(0))));
        a.store().ensure("CODA", "t.coda", null, "every 1h", "Europe/Rome", true, Map.of());
        a.store().runNow("CODA");
        a.execute("CODA");
        assertTrue(a.store().runs("CODA", 10).isEmpty(), "niente storico per i giri a vuoto");
        assertNotNull(a.store().definition("CODA").orElseThrow().lastIdleAt(), "ma si vede che il job e' vivo");
        pending.set(3);
        a.store().runNow("CODA");
        a.execute("CODA");
        assertEquals(Map.of("elaborati", 3L), a.store().runs("CODA", 10).get(0).counts());
    }
}
