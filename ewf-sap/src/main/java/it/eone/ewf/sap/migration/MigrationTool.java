package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.SimpleDataSource;
import it.eone.ewf.sap.SapDestinations;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Migrazione della configurazione /EWF/ verso il DB eWF.
 *
 * Argomenti: &lt;destinazione SAP&gt; &lt;url JDBC&gt; [--apply] [--exclude=P1,UC_*] [--report=cartella]
 * --exclude sostituisce i default (EONE_TRASFERTE, UC_*); '*' finale = prefisso.
 * Esempio:   EWF_DEV jdbc:postgresql://localhost:5432/ewf
 *
 * Senza --apply e' una PROVA GENERALE: scrive e valida tutto in una transazione e poi la annulla.
 * Variabili d'ambiente: quelle di SapDestinations, piu' EWF_DB_USER (default ewf_owner) e EWF_DB_PASSWORD.
 */
public final class MigrationTool {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: MigrationTool <destinazione SAP> <url JDBC> [--apply] [--exclude=P1,P2] [--report=cartella]");
            return;
        }
        String destination = args[0];
        String url = args[1];
        boolean apply = Arrays.asList(args).contains("--apply");
        Set<String> exclude = new LinkedHashSet<>(LegacyMapper.Options.defaults().excludedProcesses());
        Path reportDir = Path.of(".");
        for (String a : args) {
            if (a.startsWith("--exclude=")) {
                exclude.clear();
                for (String p : a.substring(10).split(",")) if (!p.isBlank()) exclude.add(p.trim());
            }
            if (a.startsWith("--report=")) reportDir = Path.of(a.substring(9));
        }
        String dbUser = System.getenv().getOrDefault("EWF_DB_USER", "ewf_owner");
        String dbPwd = System.getenv("EWF_DB_PASSWORD");
        if (dbPwd == null) throw new IllegalStateException("Variabile EWF_DB_PASSWORD non impostata");

        System.out.println("1/4 Lettura configurazione da SAP (" + destination + ")...");
        SapDestinations.register();
        LegacyConfig cfg = new SapConfigReader().read(destination);
        for (String t : LegacyConfig.TABLES) System.out.printf("    %-16s %5d righe%n", t, cfg.rows(t).size());

        System.out.println("2/4 Mappatura sul nuovo modello (esclusi: " + exclude + ")...");
        MigrationPlan plan = new LegacyMapper(new LegacyMapper.Options(exclude)).map(cfg);

        System.out.println("3/4 " + (apply ? "SCRITTURA nel DB " : "Prova generale sul DB (con rollback) ") + url + " ...");
        JdbcTransactions db = new JdbcTransactions(new SimpleDataSource(url, dbUser, dbPwd));
        MigrationWriter writer = new MigrationWriter(db);
        MigrationWriter.Result result = writer.write(plan, !apply);
        result.written().forEach((k, v) -> System.out.printf("    %-24s %5d%n", k, v));

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Path html = reportDir.resolve("migrazione-" + destination + "-" + stamp + (apply ? "-applicata" : "-prova") + ".html");
        HtmlReport.write(html, destination + " -> " + url, plan, result);
        System.out.println("4/4 Rapporto: " + html.toAbsolutePath());

        long errors = plan.issues.stream().filter(i -> i.severity() == Issue.Severity.ERROR).count();
        long validation = result.versions().stream().flatMap(v -> v.problems().stream())
                .filter(p -> "ERROR".equals(p.severity())).count();
        long drafts = result.versions().stream().filter(v -> !v.active()).count();
        System.out.println();
        System.out.println(apply ? "Migrazione APPLICATA." : "Prova generale completata: nessun dato scritto.");
        System.out.println("Errori di mappatura: " + errors + " · errori di validazione: " + validation
                + " · versioni rimaste in bozza: " + drafts);
        result.versions().stream().filter(v -> !v.active())
                .forEach(v -> System.out.println("    bozza: " + v.processType()));
        if (apply) for (Map.Entry<String, Long> e : writer.countRows().entrySet())
            System.out.printf("    %-28s %5d%n", e.getKey(), e.getValue());
    }
}
