package it.eone.ewf.s4cloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Collegamento a un tenant S/4HANA Cloud Public con un utente di comunicazione (Basic Auth).
 *
 * Configurazione da variabili d'ambiente, con il nome del sistema (backend_system.connection_ref) al posto di NAME:
 * <pre>
 *   EWF_S4_NAME_URL     es. https://my434383-api.s4hana.cloud.sap
 *   EWF_S4_NAME_USER    utente di comunicazione, es. EWF_COMM
 *   EWF_S4_NAME_PASSWD  password (solo variabile d'ambiente: mai in file, WAR o Git)
 * </pre>
 * Solo letture (GET). Gli errori di rete o HTTP diventano IllegalStateException con un testo leggibile.
 */
public final class S4Connection {

    /** Esegue una GET e restituisce il corpo; separato per poterlo sostituire nei test. */
    @FunctionalInterface
    public interface Transport {
        String get(String url) throws IOException;
    }

    private final String name;
    private final String baseUrl;
    private final Transport transport;

    public S4Connection(String name, String baseUrl, Transport transport) {
        this.name = Objects.requireNonNull(name);
        this.baseUrl = stripSlash(Objects.requireNonNull(baseUrl));
        this.transport = Objects.requireNonNull(transport);
    }

    /** Legge URL, utente e password da lookup (in ewfserver: AppConfig, che guarda le variabili d'ambiente). */
    public static S4Connection fromEnv(String name, Function<String, String> lookup) {
        String p = "EWF_S4_" + name.toUpperCase() + "_";
        String url = lookup.apply(p + "URL"), user = lookup.apply(p + "USER"), pwd = lookup.apply(p + "PASSWD");
        if (url == null || url.isBlank()) throw new IllegalStateException(p + "URL non impostata");
        if (user == null || user.isBlank()) throw new IllegalStateException(p + "USER non impostata");
        if (pwd == null || pwd.isBlank()) throw new IllegalStateException(p + "PASSWD non impostata");
        return new S4Connection(name, url, basic(user, pwd, Duration.ofSeconds(30)));
    }

    public String name() { return name; }

    /** GET relativa alla base (path che inizia con /sap/opu/...), risposta JSON come oggetto. */
    public Map<String, Object> getJson(String path) {
        String url = baseUrl + path;
        String body;
        try {
            body = transport.get(url);
        } catch (IOException e) {
            throw new IllegalStateException("S/4 " + name + " non raggiungibile: " + e.getMessage(), e);
        }
        try {
            return Json.object(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("S/4 " + name + ": risposta non JSON (" + e.getMessage() + ")", e);
        }
    }

    /** Trasporto reale: java.net.http con Basic Auth. */
    static Transport basic(String user, String password, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return url -> {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Authorization", auth).header("Accept", "application/json").GET().build();
            HttpResponse<String> rs;
            try {
                rs = http.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrotto", e);
            }
            if (rs.statusCode() == 401) throw new IOException("HTTP 401: utente o password di comunicazione errati");
            if (rs.statusCode() == 403) throw new IOException("HTTP 403: " + odataError(rs.body()));
            if (rs.statusCode() == 404) throw new NotFound(odataError(rs.body()));
            if (rs.statusCode() / 100 != 2) throw new IOException("HTTP " + rs.statusCode() + ": " + odataError(rs.body()));
            return rs.body();
        };
    }

    /** 404: il documento non esiste (non e' un errore di collegamento). */
    public static final class NotFound extends IOException {
        private static final long serialVersionUID = 1L;
        public NotFound(String message) { super(message); }
    }

    /** Testo dell'errore OData ({"error":{"code":..,"message":..}}), o l'inizio del corpo. */
    @SuppressWarnings("unchecked")
    static String odataError(String body) {
        if (body == null || body.isBlank()) return "(nessun dettaglio)";
        try {
            Object e = Json.object(body).get("error");
            if (e instanceof Map<?, ?> m) {
                Object msg = m.get("message");
                if (msg instanceof Map<?, ?> mm) msg = mm.get("value");          // formato V2
                return m.get("code") + " " + msg;
            }
        } catch (IllegalArgumentException ignored) {
            // non JSON
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
