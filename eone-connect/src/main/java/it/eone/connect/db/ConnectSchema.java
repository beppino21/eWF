package it.eone.connect.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Schema eone_connect nel DB dell'applicazione. Le versioni sono script V1.sql, V2.sql ... nel classpath.
 *  - migrate(): crea/aggiorna lo schema; va eseguito con l'utente proprietario (es. ewf_owner) e concede
 *    i permessi al ruolo applicativo (es. ewf_app);
 *  - verify(): all'avvio dell'applicazione, controlla che lo schema sia aggiornato (non modifica nulla).
 */
public final class ConnectSchema {

    public static final String SCHEMA = "eone_connect";
    /** Versione richiesta da questa libreria. */
    public static final int VERSION = 1;

    private ConnectSchema() { }

    public static int installedVersion(Db db) {
        return db.tx(c -> {
            // la sottoquery viene analizzata anche se il CASE non la esegue: prima si guarda se la tabella c'e'
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT to_regclass('" + SCHEMA + ".schema_version') IS NOT NULL")) {
                    rs.next();
                    if (!rs.getBoolean(1)) return 0;
                }
                try (ResultSet rs = st.executeQuery("SELECT coalesce(max(version), 0) FROM " + SCHEMA + ".schema_version")) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    public static void verify(Db db) {
        int v = installedVersion(db);
        if (v < VERSION)
            throw new IllegalStateException("Schema " + SCHEMA + " alla versione " + v + ", richiesta " + VERSION
                    + ": eseguire it.eone.connect.db.ConnectSchemaTool con l'utente proprietario del DB");
    }

    /** Applica le versioni mancanti; appRole (puo' essere null) riceve i permessi di lettura/scrittura. */
    public static int migrate(Db db, String appRole) {
        return db.tx(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
                st.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".schema_version ("
                        + "version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
            }
            int current;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT coalesce(max(version), 0) FROM " + SCHEMA + ".schema_version")) {
                rs.next();
                current = rs.getInt(1);
            }
            for (int v = current + 1; v <= VERSION; v++) {
                try (Statement st = c.createStatement()) {
                    st.execute(script(v));
                    st.execute("INSERT INTO " + SCHEMA + ".schema_version(version) VALUES (" + v + ")");
                }
            }
            if (appRole != null && !appRole.isBlank()) grant(c, appRole);
            return VERSION;
        });
    }

    private static void grant(Connection c, String role) throws SQLException {
        if (!role.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Ruolo non valido: " + role);
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + role + "'")) {
            if (!rs.next())
                throw new IllegalArgumentException("Il ruolo " + role + " non esiste in questo PostgreSQL: crearlo prima "
                        + "(CREATE ROLE " + role + " LOGIN) oppure indicare quello usato dall'applicazione");
        }
        try (Statement st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + role);
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + SCHEMA + " TO " + role);
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + SCHEMA + " TO " + role);
        }
    }

    private static String script(int v) {
        String name = "V" + v + ".sql";
        try (InputStream in = ConnectSchema.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Script " + name + " mancante");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
