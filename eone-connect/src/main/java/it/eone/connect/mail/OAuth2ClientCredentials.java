package it.eone.connect.mail;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token OAuth2 "client credentials" (applicazione, senza utente), tenuto in memoria fino a un minuto prima
 * della scadenza. Per Microsoft 365: registrazione dell'applicazione in Entra ID con permesso SMTP.SendAsApp,
 * service principal registrato in Exchange Online e permesso sulla casella mittente.
 */
public final class OAuth2ClientCredentials implements SmtpConfig.TokenProvider {

    private static final Pattern TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EXPIRES = Pattern.compile("\"expires_in\"\\s*:\\s*\"?(\\d+)");

    private final URI tokenUri;
    private final String clientId;
    private final Supplier<String> clientSecret;
    private final String scope;
    private final HttpClient http;
    private final Clock clock;
    private String cached;
    private Instant validUntil = Instant.MIN;

    public OAuth2ClientCredentials(URI tokenUri, String clientId, Supplier<String> clientSecret, String scope,
                                   HttpClient http, Clock clock) {
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.http = http;
        this.clock = clock;
    }

    public static OAuth2ClientCredentials microsoft(String tenant, String clientId, Supplier<String> secret, String scope) {
        return new OAuth2ClientCredentials(
                URI.create("https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token"),
                clientId, secret, scope,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), Clock.systemUTC());
    }

    @Override
    public synchronized String token() throws MailException {
        if (cached != null && clock.instant().isBefore(validUntil)) return cached;
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret.get())
                + "&scope=" + enc(scope);
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(tokenUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new MailException("Token OAuth2 non ottenuto: " + e.getMessage(), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailException("Token OAuth2: interrotto", false, e);
        }
        Matcher t = TOKEN.matcher(r.body());
        if (r.statusCode() != 200 || !t.find()) {
            // 4xx = configurazione sbagliata (client, segreto, permessi): inutile ritentare a raffica
            boolean permanent = r.statusCode() >= 400 && r.statusCode() < 500;
            throw new MailException("Token OAuth2 rifiutato (HTTP " + r.statusCode() + "): "
                    + r.body().substring(0, Math.min(300, r.body().length())), permanent);
        }
        Matcher x = EXPIRES.matcher(r.body());
        long secs = x.find() ? Long.parseLong(x.group(1)) : 300;
        cached = t.group(1);
        validUntil = clock.instant().plusSeconds(Math.max(0, secs - 60));
        return cached;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
