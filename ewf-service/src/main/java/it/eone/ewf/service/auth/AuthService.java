package it.eone.ewf.service.auth;

import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.ServiceException.Code;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

/**
 * Login con gli utenti di eWF (ewf_org.app_user). Nel DB solo l'hash PBKDF2, mai la password.
 * Dopo {@link #MAX_FAILURES} tentativi falliti consecutivi l'utente resta bloccato per {@link #LOCK_TIME}.
 * Il messaggio d'errore e' sempre lo stesso, per non rivelare se un utente esiste o e' bloccato.
 * Le password provvisorie (reset) obbligano al cambio al primo accesso.
 */
public final class AuthService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_TIME = Duration.ofMinutes(15);
    public static final int MIN_LENGTH = 10;
    static final String FAILED = "Utente o password non validi";

    /** Utente collegato. mustChangePassword: l'applicazione deve chiedere subito una nuova password. */
    public record Session(long userId, String login, String displayName, String sapUser, boolean mustChangePassword) {}

    private final JdbcTransactions db;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(JdbcTransactions db, PasswordHasher hasher, Clock clock) {
        this.db = db;
        this.hasher = hasher;
        this.clock = clock;
    }

    public Session login(String login, char[] password) {
        try {
            if (login == null || login.isBlank() || password == null || password.length == 0)
                throw new ServiceException(Code.AUTH_FAILED, FAILED);
            Session s = db.inTransaction(() -> db.with(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT id, login, display_name, sap_user, active, password_hash, password_must_change,
                               failed_logins, locked_until
                          FROM ewf_org.app_user WHERE upper(login) = upper(?) FOR UPDATE""")) {
                    ps.setString(1, login.trim());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            hasher.verify(password, null);                 // stesso tempo di risposta
                            return null;
                        }
                        long id = rs.getLong("id");
                        Timestamp locked = rs.getTimestamp("locked_until");
                        Instant now = clock.instant();
                        if (!rs.getBoolean("active") || (locked != null && locked.toInstant().isAfter(now))) {
                            hasher.verify(password, null);
                            return null;
                        }
                        String hash = rs.getString("password_hash");
                        if (!hasher.verify(password, hash)) {
                            recordFailure(id, rs.getInt("failed_logins") + 1, now);
                            return null;
                        }
                        String newHash = hasher.needsRehash(hash) ? hasher.hash(password) : null;
                        try (PreparedStatement up = c.prepareStatement("""
                                UPDATE ewf_org.app_user
                                   SET failed_logins = 0, locked_until = NULL, last_login_at = ?,
                                       password_hash = coalesce(?, password_hash)
                                 WHERE id = ?""")) {
                            up.setTimestamp(1, Timestamp.from(now));
                            up.setString(2, newHash);
                            up.setLong(3, id);
                            up.executeUpdate();
                        }
                        return new Session(id, rs.getString("login"), rs.getString("display_name"),
                                rs.getString("sap_user"), rs.getBoolean("password_must_change"));
                    }
                }
            }));
            if (s == null) throw new ServiceException(Code.AUTH_FAILED, FAILED);   // dopo il commit del contatore
            return s;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private void recordFailure(long userId, int failures, Instant now) {
        boolean lock = failures >= MAX_FAILURES;
        db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ewf_org.app_user SET failed_logins = ?, locked_until = ? WHERE id = ?")) {
                ps.setInt(1, lock ? 0 : failures);
                ps.setTimestamp(2, lock ? Timestamp.from(now.plus(LOCK_TIME)) : null);
                ps.setLong(3, userId);
                return ps.executeUpdate();
            }
        });
    }

    /** Cambio password da parte dell'utente: serve quella attuale (anche se provvisoria). */
    public void changePassword(long userId, char[] current, char[] newPassword) {
        try {
            db.inTransaction(() -> db.with(c -> {
                String login;
                String hash;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT login, password_hash FROM ewf_org.app_user WHERE id = ? AND active FOR UPDATE")) {
                    ps.setLong(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Utente non trovato");
                        login = rs.getString(1);
                        hash = rs.getString(2);
                    }
                }
                if (current == null || !hasher.verify(current, hash))
                    throw new ServiceException(Code.INVALID, "La password attuale non e' corretta");
                checkPolicy(login, current, newPassword);
                setHash(c, userId, hasher.hash(newPassword), false);
                return null;
            }));
        } finally {
            if (current != null) Arrays.fill(current, '\0');
            if (newPassword != null) Arrays.fill(newPassword, '\0');
        }
    }

    /**
     * Password provvisoria generata (amministratore o primo accesso): la si mostra una volta sola a chi la
     * consegna, nel DB resta solo l'hash. L'utente dovra' cambiarla al primo accesso; l'eventuale blocco cade.
     */
    public String resetPassword(long userId) {
        char[] pwd = generate(14);
        try {
            String hash = hasher.hash(pwd);
            int n = db.with(c -> setHash(c, userId, hash, true));
            if (n == 0) throw new ServiceException(Code.NOT_FOUND, "Utente non trovato");
            return new String(pwd);
        } finally {
            Arrays.fill(pwd, '\0');
        }
    }

    private static int setHash(java.sql.Connection c, long userId, String hash, boolean mustChange) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE ewf_org.app_user
                   SET password_hash = ?, password_must_change = ?, failed_logins = 0, locked_until = NULL,
                       updated_at = now()
                 WHERE id = ?""")) {
            ps.setString(1, hash);
            ps.setBoolean(2, mustChange);
            ps.setLong(3, userId);
            return ps.executeUpdate();
        }
    }

    /** Regole minime: lunghezza, lettere e cifre, diversa dal login e da quella attuale. */
    static void checkPolicy(String login, char[] current, char[] pwd) {
        if (pwd == null || pwd.length < MIN_LENGTH)
            throw ServiceException.of(Code.INVALID, "La nuova password deve avere almeno %d caratteri", MIN_LENGTH);
        boolean letter = false, digit = false;
        for (char ch : pwd) {
            if (Character.isLetter(ch)) letter = true;
            else if (Character.isDigit(ch)) digit = true;
        }
        if (!letter || !digit)
            throw new ServiceException(Code.INVALID, "La nuova password deve contenere lettere e cifre");
        String p = new String(pwd).toLowerCase(Locale.ROOT);
        if (login != null && p.contains(login.toLowerCase(Locale.ROOT)))
            throw new ServiceException(Code.INVALID, "La nuova password non puo' contenere il nome utente");
        if (current != null && Arrays.equals(pwd, current))
            throw new ServiceException(Code.INVALID, "La nuova password deve essere diversa da quella attuale");
    }

    /** Senza caratteri ambigui (0/O, 1/l/I): la password provvisoria si detta o si trascrive. */
    private char[] generate(int len) {
        String letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";
        String digits = "23456789";
        String all = letters + digits;
        char[] out = new char[len];
        for (int i = 0; i < len; i++) out[i] = all.charAt(random.nextInt(all.length()));
        int d = random.nextInt(len);
        out[d] = digits.charAt(random.nextInt(digits.length()));                        // almeno una cifra
        out[(d + 1 + random.nextInt(len - 1)) % len] = letters.charAt(random.nextInt(letters.length())); // e una lettera
        return out;
    }
}
