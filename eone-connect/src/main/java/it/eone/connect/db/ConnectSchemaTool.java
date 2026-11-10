package it.eone.connect.db;

/**
 * Crea o aggiorna lo schema eone_connect nel DB di un'applicazione.
 * Argomenti: &lt;url JDBC&gt; &lt;utente proprietario&gt; [ruolo applicativo]
 * Password dell'utente proprietario: variabile d'ambiente CONNECT_DB_PASSWORD (mai negli argomenti).
 * Esempio: jdbc:postgresql://localhost:5432/ewf ewf_owner ewf_app
 */
public final class ConnectSchemaTool {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: <url JDBC> <utente proprietario> [ruolo applicativo]   (password in CONNECT_DB_PASSWORD)");
            return;
        }
        Db db = new Db(new SimpleDataSource(args[0], args[1], System.getenv("CONNECT_DB_PASSWORD")));
        int before = ConnectSchema.installedVersion(db);
        int after = ConnectSchema.migrate(db, args.length > 2 ? args[2] : null);
        System.out.println("Schema " + ConnectSchema.SCHEMA + ": versione " + before + " -> " + after
                + (args.length > 2 ? " (permessi a " + args[2] + ")" : ""));
    }
}
