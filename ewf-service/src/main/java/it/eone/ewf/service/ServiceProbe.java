package it.eone.ewf.service;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.Requests.PathEntry;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcEngineFactory;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.JdbcUserDirectory;
import it.eone.ewf.engine.jdbc.SimpleDataSource;
import it.eone.ewf.service.Views.*;
import it.eone.ewf.service.auth.AuthService;
import it.eone.ewf.service.auth.PasswordHasher;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Prova da console del livello di servizio, in SOLA LETTURA: login (con cambio della password provvisoria),
 * lista delle attivita', dettaglio di un processo. Niente approvazioni: qui non ci sono gli handler SAP.
 * Database da EWF_DB_URL / EWF_DB_USER (default ewf_app) / EWF_DB_PASSWORD (solo da ambiente).
 * Nella console di Eclipse la password si vede mentre la si scrive; da terminale no.
 */
public final class ServiceProbe {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    private final Console console = System.console();
    private final BufferedReader in = DbEnv.IN;

    public static void main(String[] args) throws IOException {
        new ServiceProbe().run();
    }

    private void run() throws IOException {
        SimpleDataSource ds = DbEnv.dataSource();
        JdbcTransactions db = new JdbcTransactions(ds);
        Clock clock = Clock.system(ROME);
        AuthService auth = new AuthService(db, new PasswordHasher(), clock);
        WorkflowEngine engine = JdbcEngineFactory.create(ds, code -> Optional.empty(), docType -> Optional.empty(),
                (m, cur, d) -> m, clock, new EngineConfig("WFADM", ROME));
        EwfService svc = new EwfService(engine, db, new JdbcUserDirectory(db), code -> Optional.empty(),
                EwfService.Backends.none(), clock, ROME, null);

        System.out.println("eWF - prova del login");
        AuthService.Session s = null;
        while (s == null) {
            String login = line("Utente: ");
            if (login == null) return;
            try {
                s = auth.login(login, password("Password: "));
            } catch (ServiceException e) {
                System.out.println("  " + e.getMessage());
            }
        }
        System.out.println("Benvenuto " + s.displayName() + (s.sapUser() == null ? "" : " (utente SAP " + s.sapUser() + ")"));

        if (s.mustChangePassword()) {
            System.out.println("La password e' provvisoria: sceglierne una nuova (almeno "
                    + AuthService.MIN_LENGTH + " caratteri, lettere e cifre, senza il nome utente).");
            char[] current = password("Password attuale: ");
            while (true) {
                char[] p1 = password("Nuova password: ");
                char[] p2 = password("Ripeti la nuova password: ");
                if (!java.util.Arrays.equals(p1, p2)) {
                    System.out.println("  Le due password non coincidono");
                    continue;
                }
                try {
                    auth.changePassword(s.userId(), current.clone(), p1);
                    System.out.println("  Password cambiata.");
                    break;
                } catch (ServiceException e) {
                    System.out.println("  " + e.getMessage());
                    if (e.getMessage().contains("attuale")) current = password("Password attuale: ");
                }
            }
        }

        list(svc, s.userId());
        while (true) {
            String cmd = line("\nComandi: lista | dettaglio <processo> | scheda <processo> | esci > ");
            if (cmd == null || cmd.equalsIgnoreCase("esci")) return;
            try {
                if (cmd.equalsIgnoreCase("lista")) list(svc, s.userId());
                else if (cmd.toLowerCase().startsWith("dettaglio ")) detail(svc, s.userId(), Long.parseLong(cmd.substring(10).trim()));
                else if (cmd.toLowerCase().startsWith("scheda ")) sheet(svc, s.userId(), Long.parseLong(cmd.substring(7).trim()));
                else System.out.println("  Comando non riconosciuto");
            } catch (ServiceException e) {
                System.out.println("  " + e.getMessage());
            } catch (NumberFormatException e) {
                System.out.println("  Numero di processo non valido");
            }
        }
    }

    private static void list(EwfService svc, long userId) {
        Worklist w = svc.worklist(userId, WorklistFilter.all());
        System.out.println("\nAttivita' da fare: " + w.items().size());
        w.typeCounts().forEach(t -> System.out.printf("  %-12s %-35s %d%n", t.processType(), t.text(), t.count()));
        for (WorkItem i : w.items()) {
            String why = i.reason() == Reason.OWN ? "" : i.reason() == Reason.SUBSTITUTE ? " [per " + i.forWhom() + "]"
                    : " [team " + i.forWhom() + "]";
            System.out.printf("  %-6s %3d gg  processo %d  %s %s  %s%s%n    %s%n", i.light(), i.daysWaiting(),
                    i.processId(), i.docType(), i.documentKey(), i.taskText(), why,
                    i.summary() == null ? "" : i.summary());
        }
    }

    private static void detail(EwfService svc, long userId, long processId) {
        ProcessDetail d = svc.detail(userId, processId);
        Header h = d.header();
        System.out.printf("%nProcesso %d  %s  %s %s  stato %s%n", h.processId(), h.processTypeText(), h.docType(),
                h.documentKey(), h.status());
        if (h.value() != null) System.out.println("  Valore: " + EwfService.formatAmount(h.value().amount()) + " " + h.value().currency());
        System.out.println("  Avviato da " + h.starter() + " il " + DAY.format(h.startedAt()));
        System.out.println("  Percorso:");
        for (PathEntry p : d.path())
            System.out.printf("    %-8s %3d  %-40s %s%s%n", p.kind(), p.stepNo(), p.taskText(),
                    p.who() == null ? "" : p.who(),
                    p.kind() == PathEntry.Kind.CHOICE ? " " + p.options() : p.note() == null ? "" : "  (" + p.note() + ")");
        if (!d.history().isEmpty()) {
            System.out.println("  Storico:");
            for (HistoryEntry e : d.history())
                System.out.printf("    %s  %-9s %-40s %s%s%s%n", DAY.format(e.closedAt() == null ? e.createdAt() : e.closedAt()),
                        e.status(), e.taskText(), e.actedBy() == null ? "" : e.actedBy(),
                        e.forWhom() == null ? "" : " per " + e.forWhom(), e.note() == null ? "" : "  \"" + e.note() + "\"");
        }
        d.comments().forEach(c -> System.out.println("  Commento di " + c.author() + ": " + c.text()));
        MyTask m = d.myTask();
        System.out.println(m == null ? "  Nessuna attivita' tua su questo processo."
                : "  Tocca a te: " + m.taskText() + (m.webActionable() ? "" : " (solo in SAP GUI)")
                  + (m.options().isEmpty() ? "" : "  opzioni " + m.options().stream().map(Option::label).toList()));
    }

    /** Riepilogo approvazione in PDF, nella cartella temporanea del sistema. */
    private static void sheet(EwfService svc, long userId, long processId) throws IOException {
        FileContent f = svc.documentSheet(userId, processId, "EWF");
        java.nio.file.Path out = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), f.fileName());
        java.nio.file.Files.write(out, f.content());
        System.out.println("  Scheda salvata in " + out.toAbsolutePath());
    }

    private String line(String prompt) throws IOException {
        if (console != null) {
            String l = console.readLine("%s", prompt);
            return l == null ? null : l.trim();
        }
        System.out.print(prompt);
        System.out.flush();
        String l = in.readLine();
        return l == null ? null : l.trim();
    }

    private char[] password(String prompt) throws IOException {
        if (console != null) return console.readPassword(prompt);
        String l = line(prompt);
        return l == null ? new char[0] : l.toCharArray();
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
