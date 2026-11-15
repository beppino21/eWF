package it.eone.ewf.ewfserver.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Configurazione di eWF, come nel ticketing: un file di properties ESTERNO al WAR (sopravvive ai redeploy),
 * con ripiego sulle variabili d'ambiente dallo stesso nome.
 *
 * Percorso: system property "ewf.config.path", altrimenti C:\eone\ewf\config.properties (Windows) o
 * /opt/eone/ewf/config.properties (Linux); il primo che esiste.
 *
 * Differenza voluta rispetto al ticketing: le PASSWORD non si leggono mai dal file, solo dalle variabili
 * d'ambiente di Tomcat (setenv o servizio). Se nel file ce n'e' una, viene ignorata e segnalata nel log.
 */
public final class AppConfig {
    private AppConfig() {}

    /** Chiavi che si leggono solo dall'ambiente. */
    private static final Set<String> SECRETS = Set.of("EWF_DB_PASSWORD", "EONE_SMTP_PASSWORD",
            "EONE_SMTP_OAUTH_CLIENT_SECRET", "CONNECT_DB_PASSWORD");

    private static Properties props;

    private static synchronized Properties props() {
        if (props != null) return props;
        props = new Properties();
        for (String candidate : candidatePaths()) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                    System.out.println("[AppConfig] Configurazione caricata da " + candidate);
                    for (String s : SECRETS)
                        if (props.containsKey(s))
                            System.err.println("[AppConfig] " + s + " presente nel file: IGNORATA, va impostata come variabile d'ambiente");
                    return props;
                } catch (Exception e) {
                    System.err.println("[AppConfig] Errore lettura " + candidate + ": " + e.getMessage());
                }
            }
        }
        System.out.println("[AppConfig] Nessun config.properties in " + candidatePaths() + ": uso le variabili d'ambiente");
        return props;
    }

    private static List<String> candidatePaths() {
        List<String> l = new ArrayList<>();
        String override = System.getProperty("ewf.config.path");
        if (override != null && !override.isBlank()) l.add(override.trim());
        l.add("C:\\eone\\ewf\\config.properties");
        l.add("/opt/eone/ewf/config.properties");
        return l;
    }

    /** File, poi ambiente, poi default. Per le password usare {@link #secret}. */
    public static String get(String key, String def) {
        if (!SECRETS.contains(key)) {
            String f = props().getProperty(key);
            if (f != null && !f.isBlank()) return f.trim();
        }
        String e = System.getenv(key);
        return e != null && !e.isBlank() ? e.trim() : def;
    }

    /** Solo variabile d'ambiente. */
    public static String secret(String key) {
        String e = System.getenv(key);
        return e == null || e.isBlank() ? null : e;
    }

    public static boolean getBoolean(String key, boolean def) {
        String v = get(key, null);
        return v == null ? def : "true".equalsIgnoreCase(v) || "1".equals(v) || "si".equalsIgnoreCase(v);
    }

    public static int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
