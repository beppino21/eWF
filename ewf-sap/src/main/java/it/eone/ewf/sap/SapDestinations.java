package it.eone.ewf.sap;

import com.sap.conn.jco.ext.DestinationDataEventListener;
import com.sap.conn.jco.ext.DestinationDataProvider;
import com.sap.conn.jco.ext.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Destinazioni JCo lette da file esterni, con la password da variabile d'ambiente.
 * Sostituisce il file .jcoDestination dentro le resources del PoC (password in chiaro nel WAR e in Git).
 *
 *   EWF_SAP_CONFIG_DIR            cartella con i file &lt;NOME&gt;.properties
 *   EWF_SAP_&lt;NOME&gt;_PASSWD         password dell'utente RFC della destinazione &lt;NOME&gt;
 */
public final class SapDestinations implements DestinationDataProvider {

    private static volatile boolean registered;

    private final Path configDir;

    private SapDestinations(Path configDir) {
        this.configDir = configDir;
    }

    /** Registra il provider una sola volta per JVM (JCo non ne accetta un secondo). */
    public static synchronized void register() {
        if (registered) return;
        String dir = System.getenv("EWF_SAP_CONFIG_DIR");
        if (dir == null || dir.isBlank())
            throw new IllegalStateException("Variabile EWF_SAP_CONFIG_DIR non impostata");
        Environment.registerDestinationDataProvider(new SapDestinations(Path.of(dir)));
        registered = true;
    }

    @Override
    public Properties getDestinationProperties(String name) {
        Path file = configDir.resolve(name + ".properties");
        if (!Files.isRegularFile(file)) return null;   // JCo: destinazione sconosciuta
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (p.getProperty(JCO_PASSWD) != null)
            throw new IllegalStateException("La password non deve stare in " + file + ": usare EWF_SAP_" + key(name) + "_PASSWD");
        String pwd = System.getenv("EWF_SAP_" + key(name) + "_PASSWD");
        if (pwd == null)
            throw new IllegalStateException("Variabile EWF_SAP_" + key(name) + "_PASSWD non impostata");
        p.setProperty(JCO_PASSWD, pwd);
        return p;
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    @Override public boolean supportsEvents() { return false; }
    @Override public void setDestinationDataEventListener(DestinationDataEventListener l) { }
}
