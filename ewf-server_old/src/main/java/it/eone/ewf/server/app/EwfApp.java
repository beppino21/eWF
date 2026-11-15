package it.eone.ewf.server.app;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcEngineFactory;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.JdbcUserDirectory;
import it.eone.ewf.engine.jdbc.SimpleDataSource;
import it.eone.ewf.engine.memory.RateCurrencyConverter;
import it.eone.ewf.server.config.AppConfig;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.auth.AuthService;
import it.eone.ewf.service.auth.PasswordHasher;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Servizi dell'applicazione, creati una volta per Tomcat alla prima richiesta e condivisi da tutte le sessioni.
 *
 * Configurazione (AppConfig: file esterno o ambiente):
 *   EWF_DB_URL (default jdbc:postgresql://localhost:5432/ewf), EWF_DB_USER (default ewf_app),
 *   EWF_DB_PASSWORD (solo ambiente), EWF_SAP_DEST (vuoto = nessun SAP: niente schede da SAP ne' azioni
 *   verso SAP), EWF_ZONE (default Europe/Rome), EWF_MAX_ATTACHMENT_MB (default 20),
 *   EWF_APP_BASE_URL (indirizzo pubblico, per i collegamenti nelle mail).
 * Le variabili di JCo (EWF_SAP_CONFIG_DIR, EWF_SAP_<DEST>_PASSWD) sono quelle di ewf-sap.
 */
public final class EwfApp {

    private static volatile EwfApp instance;

    private final EwfService service;
    private final AuthService auth;
    private final ZoneId zone;
    private final String sapDestination;

    public static EwfApp get() {
        EwfApp i = instance;
        if (i == null) {
            synchronized (EwfApp.class) {
                if (instance == null) instance = new EwfApp();
                i = instance;
            }
        }
        return i;
    }

    private EwfApp() {
        zone = ZoneId.of(AppConfig.get("EWF_ZONE", "Europe/Rome"));
        Clock clock = Clock.system(zone);
        String url = AppConfig.get("EWF_DB_URL", "jdbc:postgresql://localhost:5432/ewf");
        String user = AppConfig.get("EWF_DB_USER", "ewf_app");
        if (AppConfig.secret("EWF_DB_PASSWORD") == null)
            System.err.println("[EwfApp] EWF_DB_PASSWORD non impostata: il collegamento al database fallira'");
        SimpleDataSource ds = new SimpleDataSource(url, user, AppConfig.secret("EWF_DB_PASSWORD"));
        JdbcTransactions db = new JdbcTransactions(ds);

        String dest = AppConfig.get("EWF_SAP_DEST", "");
        Sap sap = dest.isBlank() ? Sap.none() : Sap.connect(dest);
        sapDestination = sap.destination();

        WorkflowEngine engine = JdbcEngineFactory.create(ds, sap.handlers(), sap.adapters(),
                new RateCurrencyConverter(), clock, new EngineConfig(EngineConfig.defaults().fallbackTeamCode(), zone));
        long maxMb = AppConfig.getInt("EWF_MAX_ATTACHMENT_MB", 20);
        service = new EwfService(engine, db, new JdbcUserDirectory(db), sap.handlers(), sap.backends(), clock, zone,
                new EwfService.Config(maxMb * 1024 * 1024, 4000));
        auth = new AuthService(db, new PasswordHasher(), clock);
        System.out.println("[EwfApp] Avviato: DB " + url + " (" + user + ")"
                + (sapDestination == null ? " - senza SAP" : " - SAP " + sapDestination));
    }

    public EwfService service() { return service; }
    public AuthService auth() { return auth; }
    public ZoneId zone() { return zone; }
    public Optional<String> sapDestination() { return Optional.ofNullable(sapDestination); }

    /** Collegamento diretto a un task, per le mail: <EWF_APP_BASE_URL>/Outest.risc?task=<id>; null se non configurato. */
    public static String taskLink(long taskId) {
        String base = AppConfig.get("EWF_APP_BASE_URL", null);
        return base == null ? null : base.replaceAll("/+$", "") + "/Outest.risc?task=" + taskId;
    }
}
