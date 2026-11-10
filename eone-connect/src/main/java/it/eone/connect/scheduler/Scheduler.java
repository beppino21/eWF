package it.eone.connect.scheduler;

import it.eone.connect.db.ConnectSchema;
import it.eone.connect.db.Db;
import it.eone.connect.db.Json;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

/**
 * Esegue i job pianificati nel DB.
 *
 * Esecuzione unica nel cluster: prima di eseguire, lo scheduler prende un lock di PostgreSQL
 * (pg_try_advisory_lock) dedicato al job, su una connessione tenuta aperta per tutta l'esecuzione.
 * Con piu' istanze (due Tomcat, BTP con due istanze) una sola esegue; le altre saltano il giro.
 * Se un'istanza muore a meta', PostgreSQL rilascia il lock con la connessione e l'esecuzione rimasta
 * RUNNING nello storico viene chiusa come ABORTED dalla prima istanza che riprende il job.
 *
 * Ogni esecuzione e' registrata in eone_connect.job_run (istanza, inizio, fine, esito, messaggio, conteggi).
 * Uno job di sistema pulisce lo storico (default: 30 giorni).
 */
public final class Scheduler implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Scheduler.class.getName());

    /** Prima chiave dei lock di PostgreSQL usati da eone_connect ("ECON"). */
    static final int LOCK_SPACE = 0x45434F4E;

    public static final String PURGE_JOB = "eone.connect.purge-runs";

    public record Options(String node, Duration tick, int workers, Duration stopGrace, Clock clock) {
        public static Options defaults() {
            return new Options(defaultNode(), Duration.ofSeconds(5), 4, Duration.ofSeconds(30), Clock.systemUTC());
        }

        public Options withTick(Duration d) { return new Options(node, d, workers, stopGrace, clock); }
        public Options withNode(String n) { return new Options(n, tick, workers, stopGrace, clock); }
    }

    private final Db db;
    private final JobStore store;
    private final JobRegistry registry;
    private final Options opt;
    private final Set<String> runningHere = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;
    private ScheduledExecutorService ticker;
    private ExecutorService workers;

    public Scheduler(Db db, JobRegistry registry, Options options) {
        this.db = db;
        this.store = new JobStore(db);
        this.registry = registry;
        this.opt = options;
        registry.register(PURGE_JOB, ctx -> {
            int days = Integer.parseInt(ctx.param("days", "30"));
            int n = store.purgeRuns(opt.clock().instant().minus(Duration.ofDays(days)));
            return JobResult.ok("Eliminate " + n + " esecuzioni piu' vecchie di " + days + " giorni").with("deleted", n);
        });
    }

    public JobStore store() { return store; }
    public String node() { return opt.node(); }

    /** Verifica lo schema, registra i job di sistema e avvia il giro periodico. */
    public synchronized void start() {
        if (ticker != null) return;
        ConnectSchema.verify(db);
        store.ensure(PURGE_JOB, PURGE_JOB, "Pulizia dello storico dei job", "daily 03:17", "Europe/Rome", true,
                Map.of("days", "30"));
        stopping = false;
        workers = Executors.newFixedThreadPool(opt.workers(), r -> {
            Thread t = new Thread(r, "eone-job");
            t.setDaemon(true);
            return t;
        });
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eone-scheduler");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::safeTick, 0, opt.tick().toMillis(), TimeUnit.MILLISECONDS);
        LOG.log(System.Logger.Level.INFO, "Scheduler avviato su {0} (giro ogni {1})", opt.node(), opt.tick());
    }

    /** Ferma il giro, lascia finire i job in corso entro stopGrace, poi li interrompe. */
    public synchronized void stop() {
        stopping = true;
        if (ticker == null) return;
        ticker.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(opt.stopGrace().toMillis(), TimeUnit.MILLISECONDS)) workers.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        ticker = null;
        workers = null;
        LOG.log(System.Logger.Level.INFO, "Scheduler fermato su {0}", opt.node());
    }

    @Override public void close() { stop(); }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Giro dello scheduler fallito: " + e.getMessage(), e);
        }
    }

    /** Un giro: lancia i job scaduti che non stanno gia' girando qui. */
    void tick() {
        if (stopping) return;
        for (JobDefinition def : store.due(opt.clock().instant())) {
            if (!runningHere.add(def.code())) continue;
            try {
                workers.submit(() -> {
                    try {
                        execute(def.code());
                    } finally {
                        runningHere.remove(def.code());
                    }
                });
            } catch (RejectedExecutionException e) {
                runningHere.remove(def.code());
            }
        }
    }

    /** Esegue il job se e solo se questa istanza ottiene il lock e il job e' ancora da eseguire. */
    void execute(String code) {
        try (Connection lock = db.dataSource().getConnection()) {
            lock.setAutoCommit(true);
            if (!tryLock(lock, code)) return;                         // un'altra istanza lo sta eseguendo
            try {
                Claim claim = claim(code);
                if (claim == null) return;                            // eseguito altrove nel frattempo
                run(claim);
            } finally {
                unlock(lock, code);
            }
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING, "Job " + code + ": " + e.getMessage(), e);
        }
    }

    private record Claim(long runId, JobDefinition def) {}

    /**
     * Con il lock in mano: rilegge il job, chiude le esecuzioni rimaste appese (istanze morte),
     * registra l'esecuzione e fissa la prossima. Restituisce null se non c'e' piu' nulla da fare.
     */
    private Claim claim(String code) {
        Instant now = opt.clock().instant();
        return db.tx(c -> {
            JobDefinition def;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + JobStore.COLS
                    + " FROM eone_connect.job WHERE code = ? FOR UPDATE")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    def = JobStore.definition(rs);
                }
            }
            if (!def.enabled() || def.nextRunAt() == null || def.nextRunAt().isAfter(now)) return null;

            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE eone_connect.job_run SET status = 'ABORTED', ended_at = ?,
                           message = coalesce(message || ' - ', '') || 'interrotto: istanza terminata durante l''esecuzione'
                     WHERE job_code = ? AND status = 'RUNNING'""")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setString(2, code);
                ps.executeUpdate();
            }

            Instant next;
            try {
                Schedule s = Schedule.parse(def.schedule(), ZoneId.of(def.zone()));
                next = s.next(def.nextRunAt());                       // cadenza regolare...
                if (!next.isAfter(now)) next = s.next(now);           // ...ma senza recuperare i giri persi
            } catch (RuntimeException e) {
                next = null;                                          // pianificazione rotta: si ferma, l'errore resta nello storico
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE eone_connect.job SET next_run_at = ? WHERE code = ?")) {
                ps.setTimestamp(1, next == null ? null : Timestamp.from(next));
                ps.setString(2, code);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO eone_connect.job_run(job_code, node, started_at, status, message)
                    VALUES (?, ?, ?, 'RUNNING', ?) RETURNING id""")) {
                ps.setString(1, code);
                ps.setString(2, opt.node());
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setString(4, next == null ? "Pianificazione non valida: '" + def.schedule() + "'" : null);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Claim(rs.getLong(1), def);
                }
            }
        });
    }

    private void run(Claim claim) {
        JobDefinition def = claim.def();
        String status;
        String message;
        Map<String, Long> counts = Map.of();
        Optional<Job> job = registry.create(def.jobKey());
        if (job.isEmpty()) {
            status = "ERROR";
            message = "Job '" + def.jobKey() + "' non registrato in questa applicazione";
        } else {
            try {
                JobResult r = job.get().run(new JobContext(def.code(), claim.runId(), opt.node(), def.params(),
                        () -> stopping || Thread.currentThread().isInterrupted()));
                if (r == null) r = JobResult.ok(null);
                status = r.status().name();
                message = r.message();
                counts = r.counts();
            } catch (InterruptedException e) {
                status = "ABORTED";
                message = "Interrotto allo spegnimento";
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                status = "ERROR";
                message = t.getClass().getSimpleName() + ": " + t.getMessage();
                LOG.log(System.Logger.Level.WARNING, "Job " + def.code() + " fallito", t);
            }
        }
        if ("IDLE".equals(status)) idle(claim.runId(), def.code());
        else finish(claim.runId(), status, message, counts);
    }

    private void idle(long runId, String code) {
        Instant now = opt.clock().instant();
        db.tx(c -> {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM eone_connect.job_run WHERE id = ?");
                 PreparedStatement upd = c.prepareStatement("UPDATE eone_connect.job SET last_idle_at = ? WHERE code = ?")) {
                del.setLong(1, runId);
                del.executeUpdate();
                upd.setTimestamp(1, Timestamp.from(now));
                upd.setString(2, code);
                upd.executeUpdate();
            }
            return null;
        });
    }

    private void finish(long runId, String status, String message, Map<String, Long> counts) {
        Instant end = opt.clock().instant();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE eone_connect.job_run SET status = ?, ended_at = ?, counts = ?::jsonb,
                           message = CASE WHEN message IS NULL THEN ? ELSE message || ' - ' || coalesce(?, '') END
                     WHERE id = ?""")) {
                ps.setString(1, status);
                ps.setTimestamp(2, Timestamp.from(end));
                ps.setString(3, Json.of(counts));
                ps.setString(4, message);
                ps.setString(5, message);
                ps.setLong(6, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static boolean tryLock(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?, hashtext(?))")) {
            ps.setInt(1, LOCK_SPACE);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c, String code) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?, hashtext(?))")) {
            ps.setInt(1, LOCK_SPACE);
            ps.setString(2, code);
            ps.execute();
        } catch (SQLException e) {
            // la connessione si chiude subito dopo: PostgreSQL rilascia comunque il lock
        }
    }

    static String defaultNode() {
        String env = System.getenv("EONE_NODE");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName() + "/" + ManagementFactory.getRuntimeMXBean().getPid();
        } catch (Exception e) {
            return "node-" + ProcessHandle.current().pid();
        }
    }
}
