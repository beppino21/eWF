package it.eone.connect.mail;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Parametri SMTP. I segreti (password, client secret) non stanno qui in chiaro ma arrivano da un fornitore
 * (variabili d'ambiente, vedi fromEnv).
 *
 * Esempi:
 *  - relay aziendale senza autenticazione: host, porta 25, Security.NONE, Auth.NONE
 *  - SMTP con utente e password:           porta 587, STARTTLS, PASSWORD
 *  - Microsoft 365 (OAuth2):               smtp.office365.com, 587, STARTTLS, OAUTH2, username = casella mittente
 */
public record SmtpConfig(String host, int port, Security security, Auth auth, String username,
                         Supplier<String> password, TokenProvider tokens, String from, String heloName,
                         int timeoutMillis) {

    public enum Security { NONE, STARTTLS, SSL }
    public enum Auth { NONE, PASSWORD, OAUTH2 }

    public SmtpConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(from, "from");
        MailMessage.checkAddress(envelope(from));
        if (auth == Auth.PASSWORD && (username == null || password == null))
            throw new IllegalArgumentException("SMTP con password: servono utente e password");
        if (auth == Auth.OAUTH2 && (username == null || tokens == null))
            throw new IllegalArgumentException("SMTP OAuth2: servono la casella (username) e il fornitore di token");
        if (heloName == null || heloName.isBlank()) heloName = "localhost";
        if (timeoutMillis <= 0) timeoutMillis = 30_000;
    }

    /**
     * Da variabili d'ambiente con prefisso (es. "EONE_SMTP"):
     *   _HOST, _PORT (default 587), _SECURITY (NONE|STARTTLS|SSL, default STARTTLS), _AUTH (NONE|PASSWORD|OAUTH2,
     *   default PASSWORD se c'e' _USER, altrimenti NONE), _USER, _PASSWORD, _FROM (default _USER), _HELO,
     *   OAuth2 Microsoft: _OAUTH_TENANT, _OAUTH_CLIENT_ID, _OAUTH_CLIENT_SECRET,
     *   _OAUTH_SCOPE (default https://outlook.office365.com/.default).
     */
    public static SmtpConfig fromEnv(String prefix, java.util.function.Function<String, String> env) {
        java.util.function.Function<String, String> v = k -> {
            String s = env.apply(prefix + "_" + k);
            return s == null || s.isBlank() ? null : s.trim();
        };
        String host = v.apply("HOST");
        if (host == null) throw new IllegalStateException(prefix + "_HOST non impostata: invio mail non configurato");
        String user = v.apply("USER");
        Auth auth = v.apply("AUTH") != null ? Auth.valueOf(v.apply("AUTH").toUpperCase())
                : user != null ? Auth.PASSWORD : Auth.NONE;
        Security sec = v.apply("SECURITY") != null ? Security.valueOf(v.apply("SECURITY").toUpperCase()) : Security.STARTTLS;
        int port = v.apply("PORT") != null ? Integer.parseInt(v.apply("PORT")) : sec == Security.SSL ? 465 : 587;
        String from = v.apply("FROM") != null ? v.apply("FROM") : user;
        if (from == null) throw new IllegalStateException(prefix + "_FROM non impostata");
        TokenProvider tokens = null;
        if (auth == Auth.OAUTH2) {
            String tenant = v.apply("OAUTH_TENANT"), id = v.apply("OAUTH_CLIENT_ID"), secret = v.apply("OAUTH_CLIENT_SECRET");
            if (tenant == null || id == null || secret == null)
                throw new IllegalStateException(prefix + "_OAUTH_TENANT, _OAUTH_CLIENT_ID e _OAUTH_CLIENT_SECRET obbligatorie con OAUTH2");
            String scope = v.apply("OAUTH_SCOPE") != null ? v.apply("OAUTH_SCOPE") : "https://outlook.office365.com/.default";
            tokens = OAuth2ClientCredentials.microsoft(tenant, id, () -> secret, scope);
        }
        String pwdName = prefix + "_PASSWORD";
        return new SmtpConfig(host, port, sec, auth, user, () -> env.apply(pwdName), tokens, from,
                v.apply("HELO"), 30_000);
    }

    /** "Nome <a@b.it>" -> a@b.it */
    static String envelope(String from) {
        int lt = from.indexOf('<'), gt = from.indexOf('>');
        return lt >= 0 && gt > lt ? from.substring(lt + 1, gt).trim() : from.trim();
    }

    /** Fornitore di token di accesso (OAuth2). */
    public interface TokenProvider {
        String token() throws MailException;
    }
}
