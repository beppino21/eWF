package it.eone.ewf.service;

import it.eone.ewf.engine.jdbc.SimpleDataSource;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Collegamento al database per i programmi da riga di comando (PasswordTool, ServiceProbe).
 * Mostra quali variabili d'ambiente vede; se manca EWF_DB_PASSWORD la chiede in console (senza salvarla).
 */
public final class DbEnv {
    private DbEnv() {}

    /** Unico lettore della console per tutto il programma (due lettori bufferizzati si rubano le righe). */
    public static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));

    public static SimpleDataSource dataSource() {
        String url = env("EWF_DB_URL", "jdbc:postgresql://localhost:5432/ewf");
        String user = env("EWF_DB_USER", "ewf_app");
        String pwd = env("EWF_DB_PASSWORD", null);
        System.out.println("Database: " + url + "  utente: " + user
                + (System.getenv("EWF_DB_URL") == null ? "  (EWF_DB_URL non impostata: uso il default)" : ""));
        if (pwd == null) {
            System.out.println("La variabile EWF_DB_PASSWORD non e' impostata in questa configurazione di lancio.");
            pwd = ask("Password del database per " + user + ": ");
        }
        return new SimpleDataSource(url, user, pwd);
    }

    static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    private static String ask(String prompt) {
        Console c = System.console();
        if (c != null) {
            char[] p = c.readPassword("%s", prompt);
            return p == null ? "" : new String(p);
        }
        System.out.print(prompt);
        System.out.flush();
        try {
            String l = IN.readLine();
            return l == null ? "" : l.trim();
        } catch (IOException e) {
            return "";
        }
    }
}
