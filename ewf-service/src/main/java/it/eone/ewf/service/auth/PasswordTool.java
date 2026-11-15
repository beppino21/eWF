package it.eone.ewf.service.auth;

import it.eone.ewf.engine.jdbc.JdbcTransactions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;

/**
 * Password provvisoria per un utente eWF, da riga di comando (finche' non c'e' la gestione utenti nell'app).
 * Uso: PasswordTool <login>. Stampa la password una volta sola; al primo accesso l'utente deve cambiarla.
 * Database da EWF_DB_URL / EWF_DB_USER / EWF_DB_PASSWORD (la password solo da ambiente).
 */
public final class PasswordTool {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Uso: PasswordTool <login>");
            System.exit(2);
        }
        JdbcTransactions db = new JdbcTransactions(it.eone.ewf.service.DbEnv.dataSource());
        Long id = db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM ewf_org.app_user WHERE upper(login) = upper(?) AND active")) {
                ps.setString(1, args[0]);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        });
        if (id == null) {
            System.err.println("Utente attivo " + args[0] + " non trovato");
            System.exit(1);
        }
        String pwd = new AuthService(db, new PasswordHasher(), Clock.systemUTC()).resetPassword(id);
        System.out.println("Password provvisoria per " + args[0].toUpperCase() + ": " + pwd);
        System.out.println("Al primo accesso verra' chiesta una nuova password.");
    }

}
