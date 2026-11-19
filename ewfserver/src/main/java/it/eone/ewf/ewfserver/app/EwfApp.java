package it.eone.ewf.ewfserver.app;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcEngineFactory;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.jdbc.JdbcUserDirectory;
import it.eone.ewf.engine.memory.RateCurrencyConverter;
import it.eone.ewf.ewfserver.config.AppConfig;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.SubstitutionService;
import it.eone.ewf.service.auth.AuthService;
import it.eone.ewf.service.auth.PasswordHasher;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Servizi dell'applicazione, creati una volta per Tomcat all'avvio (JobHost) o alla prima richiesta,
 * condivisi da tutte le sessioni e chiusi alla chiusura dell'applicazione (shutdown).
 *
 * Configurazione (AppConfig: file esterno o ambiente):
 *   EWF_DB_URL (default jdbc:postgresql://localhost:5432/ewf), EWF_DB_USER (default ewf_app),
 *   EWF_DB_PASSWORD (solo ambiente), EWF_SAP_DEST (vuoto = nessun SAP: niente schede da SAP ne' azioni
 *   verso SAP), EWF_ZONE (default Europe/Rome), EWF_MAX_ATTACHMENT_MB (default 20),
 *   EWF_APP_BASE_URL (indirizzo pubblico, per i collegamenti nelle mail),
 *   EWF_DB_POOL_SIZE (connessioni al massimo nel pool, default 15).
 * Le variabili di JCo (EWF_SAP_CONFIG_DIR, EWF_SAP_<DEST>_PASSWD) sono quelle di ewf-sap.
 */
public final class EwfApp {

    private static volatile EwfApp instance;

    private final HikariDataSource dataSource;
    private final WorkflowEngine engine;
    private final EwfService service;
    private final SubstitutionService substitutions;
    private final AdminService admin;
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
        // Connessioni: driver PostgreSQL (PGSimpleDataSource, senza passare dal DriverManager, che dentro
        // Tomcat non vede i driver in WEB-INF/lib) dentro un pool (HikariCP), che tiene aperte e riusa
        // le connessioni e limita il carico sul database.
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setURL(url);
        pg.setUser(user);
        pg.setPassword(AppConfig.secret("EWF_DB_PASSWORD"));
        HikariConfig pool = new HikariConfig();
        pool.setDataSource(pg);
        pool.setPoolName("ewf");
        pool.setMaximumPoolSize(AppConfig.getInt("EWF_DB_POOL_SIZE", 15));
        pool.setMinimumIdle(2);
        pool.setConnectionTimeout(10_000);
        dataSource = new HikariDataSource(pool);
        DataSource ds = dataSource;
        System.err.println("[EwfApp] Database: pool di connessioni (max " + AppConfig.getInt("EWF_DB_POOL_SIZE", 15)
                + "), driver " + org.postgresql.Driver.getVersion());
        JdbcTransactions db = new JdbcTransactions(ds);

        String dest = AppConfig.get("EWF_SAP_DEST", "");
        Sap sap = dest.isBlank() ? Sap.none() : Sap.connect(dest);
        sapDestination = sap.destination();

        engine = JdbcEngineFactory.create(ds, sap.handlers(), sap.adapters(),
                new RateCurrencyConverter(), clock, new EngineConfig(EngineConfig.defaults().fallbackTeamCode(), zone));
        long maxMb = AppConfig.getInt("EWF_MAX_ATTACHMENT_MB", 20);
        service = new EwfService(engine, db, new JdbcUserDirectory(db), sap.handlers(), sap.backends(), clock, zone,
                new EwfService.Config(maxMb * 1024 * 1024, 4000));
        auth = new AuthService(db, new PasswordHasher(), clock);
        substitutions = new SubstitutionService(db, clock, zone);
        // Amministratori eWF = membri del team di riserva del motore (WFADM): gli stessi che ricevono i task orfani.
        admin = new AdminService(engine, db, EngineConfig.defaults().fallbackTeamCode(), clock, zone);
        System.out.println("[EwfApp] Avviato: DB " + url + " (" + user + ")"
                + (sapDestination == null ? " - senza SAP" : " - SAP " + sapDestination));
    }

    public EwfService service() { return service; }
    public SubstitutionService substitutions() { return substitutions; }
    public AdminService admin() { return admin; }
    public WorkflowEngine engine() { return engine; }
    public DataSource dataSource() { return dataSource; }
    public AuthService auth() { return auth; }
    public ZoneId zone() { return zone; }
    public Optional<String> sapDestination() { return Optional.ofNullable(sapDestination); }

    /** Chiude il pool di connessioni; la prossima richiesta ricrea i servizi. Chiamato da JobHost alla chiusura. */
    public static synchronized void shutdown() {
        EwfApp i = instance;
        instance = null;
        if (i == null) return;
        if (i.sapDestination != null) Sap.disconnect();
        if (!i.dataSource.isClosed()) {
            i.dataSource.close();
            System.out.println("[EwfApp] Chiuso il pool di connessioni" + (i.sapDestination != null ? " e il collegamento SAP" : ""));
        }
    }

    /** Collegamento diretto a un task, per le mail: <EWF_APP_BASE_URL>/Outest.risc?task=<id>; null se non configurato. */
    public static String taskLink(long taskId) {
        String base = AppConfig.get("EWF_APP_BASE_URL", null);
        return base == null ? null : base.replaceAll("/+$", "") + "/Outest.risc?task=" + taskId;
    }
}
