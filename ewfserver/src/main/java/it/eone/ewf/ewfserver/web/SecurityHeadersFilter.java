package it.eone.ewf.ewfserver.web;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import it.eone.ewf.ewfserver.config.AppConfig;

/**
 * Intestazioni HTTP di sicurezza su tutte le risposte (tra i primi
 * controlli di qualunque VAPT):
 *
 *  - X-Content-Type-Options: nosniff        → il browser non "indovina" il tipo dei file scaricati
 *  - X-Frame-Options / CSP frame-ancestors  → l'app non può essere incorniciata da siti terzi (clickjacking)
 *  - Referrer-Policy                        → l'URL interno non viene passato a siti esterni
 *  - Permissions-Policy                     → nessun accesso a camera/microfono/posizione
 *  - Strict-Transport-Security              → solo su HTTPS (anche dietro proxy con X-Forwarded-Proto)
 *
 * La Content-Security-Policy "vera" delle pagine .risc la imposta
 * CaptainCasa (HttpHeaderAttributesForPagesFilter, configurabile in
 * system.xml con <httpheaderattributesforpages content-security-policy="..."/>);
 * il default del framework consente 'unsafe-inline' e 'unsafe-eval'.
 *
 * Per PROVARE una policy più restrittiva senza rischio: CSP_REPORT_ONLY in
 * config.properties. Il browser non blocca nulla, ma segnala in console
 * (F12) ogni violazione con il prefisso "[Report Only]". Se dopo aver usato
 * tutte le schermate non compaiono violazioni, la stessa policy si può
 * portare in system.xml. Vuoto o assente = nessuna intestazione di prova.
 *
 * Disattivabile con EWF_SECURITY_HEADERS=false; HSTS con EWF_SECURITY_HSTS=false.
 * In sviluppo, per l'anteprima del designer: EWF_FRAME_ALLOWED=http://localhost:51000 (il filtro resta attivo).
 */
public class SecurityHeadersFilter implements Filter {

    private boolean abilitato = true;
    private boolean hsts      = true;
    private String  cspReportOnly;
    /** Origini in piu' autorizzate a incorniciare l'app (solo sviluppo: l'anteprima del designer). */
    private String  frameAllowed = "";

    @Override
    public void init(FilterConfig cfg) {
        abilitato = AppConfig.getBoolean("EWF_SECURITY_HEADERS", true);
        hsts      = AppConfig.getBoolean("EWF_SECURITY_HSTS", true);
        cspReportOnly = AppConfig.get("EWF_CSP_REPORT_ONLY", null);
        frameAllowed = origins(AppConfig.get("EWF_FRAME_ALLOWED", ""));
        System.out.println("[SecurityHeadersFilter] abilitato=" + abilitato + " hsts=" + hsts
            + (cspReportOnly != null ? " CSP_REPORT_ONLY=" + cspReportOnly : "")
            + (frameAllowed.isEmpty() ? "" : " incorniciabile anche da: " + frameAllowed));
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (abilitato && res instanceof HttpServletResponse && req instanceof HttpServletRequest) {
            HttpServletRequest  r = (HttpServletRequest) req;
            HttpServletResponse h = (HttpServletResponse) res;
            h.setHeader("X-Content-Type-Options", "nosniff");
            if (frameAllowed.isEmpty()) {
                h.setHeader("X-Frame-Options", "SAMEORIGIN");
                h.setHeader("Content-Security-Policy", "frame-ancestors 'self'");
            } else {
                // X-Frame-Options non sa elencare piu' origini: vale frame-ancestors, che i browser
                // considerano comunque prima di X-Frame-Options.
                h.setHeader("Content-Security-Policy", "frame-ancestors 'self' " + frameAllowed);
            }
            h.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            h.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            if (hsts && (r.isSecure() || "https".equalsIgnoreCase(r.getHeader("X-Forwarded-Proto")))) {
                h.setHeader("Strict-Transport-Security", "max-age=31536000");
            }
            if (cspReportOnly != null) {
                h.setHeader("Content-Security-Policy-Report-Only", cspReportOnly);
            }
        }
        chain.doFilter(req, res);
    }

    /**
     * EWF_FRAME_ALLOWED: origini separate da virgola o spazio, es. "http://localhost:51000".
     * Accetta solo http(s)://host[:porta]; il resto viene scartato (e segnalato), cosi' nell'intestazione
     * non finisce mai testo arbitrario.
     */
    static String origins(String raw) {
        StringBuilder out = new StringBuilder();
        for (String o : (raw == null ? "" : raw).split("[,\\s]+")) {
            if (o.isBlank()) continue;
            if (o.matches("https?://[A-Za-z0-9.-]+(:\\d{1,5})?")) {
                if (out.length() > 0) out.append(' ');
                out.append(o);
            } else {
                System.err.println("[SecurityHeadersFilter] EWF_FRAME_ALLOWED: origine non valida ignorata: " + o);
            }
        }
        return out.toString();
    }

    @Override
    public void destroy() { }
}
