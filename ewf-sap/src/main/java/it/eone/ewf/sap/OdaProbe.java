package it.eone.ewf.sap;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.EngineException;
import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcEngineFactory;
import it.eone.ewf.engine.jdbc.SimpleDataSource;
import it.eone.ewf.engine.memory.RateCurrencyConverter;
import it.eone.ewf.engine.Requests.Action;
import it.eone.ewf.engine.model.ProcessInstance;
import it.eone.ewf.engine.model.User;
import it.eone.ewf.engine.spi.UserDirectory;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.JdbcUserDirectory;
import it.eone.ewf.engine.jdbc.JdbcDefinitionStore;
import it.eone.ewf.engine.jdbc.JdbcDefinitionWriter;
import it.eone.ewf.engine.StartQueueProcessor;
import it.eone.ewf.engine.model.StartRule;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.Arrays;

/**
 * Prova a riga di comando del motore vero: DB eWF + dispatcher SAP, nessuna UI.
 * Serve a portare un OdA dall'avvio al rilascio in SAP, un'azione alla volta.
 *
 * Comandi (argomenti del programma):
 *   avvia  &lt;ebeln&gt; &lt;login&gt;                 avvia EONE_ODA sull'ordine
 *   stato  &lt;ebeln&gt;                         processi, task, ultimi eventi, anteprima
 *   ok     &lt;taskId&gt; &lt;login&gt; [nota]         approva
 *   ko     &lt;taskId&gt; &lt;login&gt; &lt;nota&gt;          respingi
 *   sposta &lt;taskId&gt; &lt;login&gt; &lt;login dest&gt;   sposta il task
 *   riprova &lt;processId&gt; &lt;login&gt;            ritenta l'azione di fine processo (processo in ERROR)
 *   coda                                  un giro della coda degli avvii /EWF/START_Q
 *   regola &lt;tipodoc&gt; &lt;processo&gt; [priorita'] [chiave=valore ...]   nuova regola di avvio
 *   regole &lt;tipodoc&gt;                      elenca le regole di avvio
 *   servizio                              scheduler eone-connect: coda degli avvii ogni 30s, fino a Ctrl+C
 *   lavori [codice]                       job pianificati e ultime esecuzioni
 *
 * Variabili d'ambiente:
 *   EWF_DB_URL (default jdbc:postgresql://localhost:5432/ewf), EWF_DB_USER (default ewf_app), EWF_DB_PASSWORD
 *   EWF_SAP_DEST (default EWF_DEV) piu' quelle di SapDestinations (EWF_SAP_CONFIG_DIR, EWF_SAP_&lt;DEST&gt;_PASSWD)
 */
public final class OdaProbe {

    private static final String PROCESS = "EONE_ODA";

    private final DataSource ds;
    private final WorkflowEngine engine;
    private final UserDirectory users;
    private final String dest;
    private final JdbcDefinitionStore engineDefs;

    private OdaProbe() {
        String url = env("EWF_DB_URL", "jdbc:postgresql://localhost:5432/ewf");
        ds = new SimpleDataSource(url, env("EWF_DB_USER", "ewf_app"), env("EWF_DB_PASSWORD", null));
        SapDestinations.register();
        dest = env("EWF_SAP_DEST", "EWF_DEV");
        engine = JdbcEngineFactory.create(ds, new SapHandlerRegistry(dest), SapDocumentAdapter.registry(dest),
                new RateCurrencyConverter(), Clock.systemDefaultZone(), EngineConfig.defaults());
        users = new JdbcUserDirectory(new JdbcTransactions(ds));
        engineDefs = new JdbcDefinitionStore(new JdbcTransactions(ds));
        System.out.println("DB " + url + " · SAP " + dest);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || (args.length < 2 && !java.util.Set.of("coda", "servizio", "lavori").contains(args[0].toLowerCase()))) {
            System.out.println("""
                    Uso:
                      avvia   <ebeln> <login>
                      stato   <ebeln>
                      ok      <taskId> <login> [nota]
                      ko      <taskId> <login> <nota>
                      sposta  <taskId> <login> <login destinatario>
                      riprova <processId> <login>
                      coda
                      regola  <tipodoc> <processo> [priorita'] [chiave=valore ...]
                      regole  <tipodoc>
                      servizio
                      lavori  [codice]""");
            return;
        }
        OdaProbe p = new OdaProbe();
        try {
            switch (args[0].toLowerCase()) {
                case "avvia" -> {
                    need(args, 3);
                    ProcessInstance pi = p.engine.start(new Requests.Start(PROCESS, args[1], null, p.user(args[2]), null));
                    System.out.println("Avviato processo " + pi.id + " (" + pi.status + ")");
                    p.status(args[1]);
                }
                case "stato" -> p.status(args[1]);
                case "ok" -> {
                    need(args, 3);
                    String note = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;
                    p.after(p.engine.act(new Requests.Act(Long.parseLong(args[1]), p.user(args[2]), Action.OK, note, null)),
                            Long.parseLong(args[1]));
                }
                case "ko" -> {
                    need(args, 4);
                    String note = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                    p.after(p.engine.act(new Requests.Act(Long.parseLong(args[1]), p.user(args[2]), Action.KO, note, null)),
                            Long.parseLong(args[1]));
                }
                case "sposta" -> {
                    need(args, 4);
                    long id = p.engine.move(Long.parseLong(args[1]), p.user(args[2]), p.user(args[3]), "Spostato da OdaProbe");
                    System.out.println("Nuovo task " + id);
                    p.statusOfTask(id);
                }
                case "riprova" -> {
                    need(args, 3);
                    ProcessInstance pi = p.engine.retryCompletion(Long.parseLong(args[1]), p.user(args[2]));
                    System.out.println("Processo " + pi.id + ": " + pi.status);
                    p.statusOfProcess(pi.id);
                }
                case "coda" -> {
                    var results = new StartQueueProcessor(p.engine, new SapStartQueue(p.dest, 20), 50).runOnce();
                    if (results.isEmpty()) System.out.println("Coda vuota");
                    results.forEach(r -> System.out.printf("  %s  %-7s %s%s%n", r.queueId(), r.status(), r.message(),
                            r.processId() == null ? "" : "  [processo " + r.processId() + "]"));
                }
                case "regola" -> {
                    need(args, 3);
                    int prio = 0;
                    Map<String, String> cond = new LinkedHashMap<>();
                    for (int i = 3; i < args.length; i++) {
                        if (args[i].contains("=")) {
                            String[] kv = args[i].split("=", 2);
                            cond.put(kv[0], kv[1]);
                        } else prio = Integer.parseInt(args[i]);
                    }
                    long id = new JdbcDefinitionWriter(new JdbcTransactions(p.ds))
                            .addStartRule(new StartRule(0, args[1], cond, prio, args[2]));
                    System.out.println("Regola di avvio " + id + ": " + args[1] + " " + cond + " -> " + args[2]
                            + " (priorita' " + prio + ")");
                }
                case "regole" -> p.engineDefs.startRules(args[1]).forEach(r -> System.out.printf(
                        "  %-4d %-4s %-40s prio %-3d -> %s%n", r.id(), r.docType(), r.conditions(), r.priority(),
                        r.processType()));
                case "servizio" -> p.service();
                case "lavori" -> p.jobs(args.length > 1 ? args[1] : null);
                default -> System.out.println("Comando sconosciuto: " + args[0]);
            }
        } catch (EngineException e) {
            System.out.println("RIFIUTATO dal motore [" + e.code() + "]: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private void after(Requests.Outcome o, long taskId) throws Exception {
        System.out.println("Esito: " + o.result() + " · processo " + o.processStatus()
                + (o.message() == null ? "" : " · " + o.message()));
        statusOfTask(taskId);
    }

    private long user(String login) {
        return users.byLogin(login.toUpperCase()).map(User::id)
                .orElseThrow(() -> new IllegalArgumentException("Utente eWF sconosciuto: " + login));
    }

    private void statusOfTask(long taskId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT process_id FROM ewf_core.task_instance WHERE id = ?")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) statusOfProcess(rs.getLong(1));
            }
        }
    }

    private void status(String ebeln) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id FROM ewf_core.process_instance
                      WHERE process_type_code = ? AND document_key = ? ORDER BY id""")) {
            ps.setString(1, PROCESS);
            ps.setString(2, ebeln);
            boolean any = false;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { any = true; statusOfProcess(rs.getLong(1)); }
            }
            if (!any) System.out.println("Nessun processo " + PROCESS + " per l'ordine " + ebeln);
        }
    }

    private void statusOfProcess(long processId) throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, document_key, status, status_reason FROM ewf_core.process_instance WHERE id = ?""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;
                    System.out.printf("%n=== Processo %d · ordine %s · %s%s%n", rs.getLong(1), rs.getString(2),
                            rs.getString(3), rs.getString(4) == null ? "" : " (" + rs.getString(4) + ")");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT net_value, currency, doc_context::text FROM ewf_core.process_instance WHERE id = ?""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next())
                        System.out.printf("  valore %s %s · contesto %s%n", nz(rs.getString(1)), nz(rs.getString(2)),
                                rs.getString(3));
                }
            }
            System.out.println("  task   step  stato      assegnatario       team    azione  agito da     nota");
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT t.id, t.step_no, t.status, a.login AS assignee, t.team_code, t.action, b.login AS acted, t.note
                      FROM ewf_core.task_instance t
                      LEFT JOIN ewf_org.app_user a ON a.id = t.assignee_id
                      LEFT JOIN ewf_org.app_user b ON b.id = t.acted_by_id
                     WHERE t.process_id = ? ORDER BY t.id""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        System.out.printf("  %-6d %-5d %-10s %-18s %-7s %-7s %-12s %s%n", rs.getLong(1), rs.getInt(2),
                                rs.getString(3), nz(rs.getString(4)), nz(rs.getString(5)), nz(rs.getString(6)),
                                nz(rs.getString(7)), nz(rs.getString(8)));
                }
            }
            System.out.println("  chiamate SAP:");
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT e.id, e.event, h.code, e.status, e.result_message
                      FROM ewf_core.handler_execution e JOIN ewf_core.handler h ON h.id = e.handler_id
                     WHERE e.process_id = ? ORDER BY e.id""")) {
                ps.setLong(1, processId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        System.out.printf("    %-6d %-14s %-28s %-8s %s%n", rs.getLong(1), rs.getString(2),
                                rs.getString(3), rs.getString(4), nz(rs.getString(5)));
                }
            }
        }
        var preview = engine.preview(processId);
        if (!preview.isEmpty()) {
            System.out.println("  prossimi step previsti:");
            preview.forEach(e -> System.out.printf("    %-5d %-40s %s%n", e.stepNo(), e.taskText(),
                    e.assignee() == null ? nz(e.note()) : e.assignee()));
        }
    }

    /** Scheduler di eone-connect sullo stesso DB di eWF (schema eone_connect). Piu' istanze insieme: gira una sola. */
    private it.eone.connect.scheduler.Scheduler scheduler() {
        var registry = new it.eone.connect.scheduler.JobRegistry()
                .register(StartQueueJob.KEY, () -> new StartQueueJob(engine, dest));
        return new it.eone.connect.scheduler.Scheduler(new it.eone.connect.db.Db(ds), registry,
                it.eone.connect.scheduler.Scheduler.Options.defaults());
    }

    private void service() throws InterruptedException {
        var s = scheduler();
        s.start();   // verifica lo schema eone_connect: se manca dice come installarlo
        s.store().ensure(StartQueueJob.CODE, StartQueueJob.KEY, "Coda degli avvii /EWF/START_Q (" + dest + ")",
                "every 30s", "Europe/Rome", true, Map.of("batch", "50"));
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        System.out.println("Scheduler attivo sul nodo " + s.node() + ". Ctrl+C (o Stop in Eclipse) per fermarlo.");
        Thread.currentThread().join();
    }

    private void jobs(String code) {
        var store = new it.eone.connect.scheduler.JobStore(new it.eone.connect.db.Db(ds));
        var fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
        java.util.function.Function<java.time.Instant, String> t = i -> i == null ? "-" : fmt.format(i);
        for (var d : store.definitions()) {
            if (code != null && !d.code().equalsIgnoreCase(code)) continue;
            System.out.printf("%s  %-8s %-22s prossimo %s  ultimo a vuoto %s  %s%n", d.code(), d.enabled() ? "attivo" : "SPENTO",
                    d.schedule(), t.apply(d.nextRunAt()), t.apply(d.lastIdleAt()), d.params());
            for (var r : store.runs(d.code(), code == null ? 3 : 20))
                System.out.printf("    %s  %-7s %-14s %s %s%n", t.apply(r.startedAt()), r.status(), r.node(),
                        r.counts().isEmpty() ? "" : r.counts(), nz(r.message()));
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static void need(String[] args, int n) {
        if (args.length < n) throw new IllegalArgumentException("Argomenti insufficienti per " + args[0]);
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
