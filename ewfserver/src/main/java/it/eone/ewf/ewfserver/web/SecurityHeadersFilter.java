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
 * Disattivabile con EWF_SECURITY_HEADERS=false; HSTS con SECURITY_HSTS=false.
 */
public class SecurityHeadersFilter implements Filter {

    private boolean abilitato = true;
    private boolean hsts      = true;
    private String  cspReportOnly;

    @Override
    public void init(FilterConfig cfg) {
        abilitato = AppConfig.getBoolean("EWF_SECURITY_HEADERS", true);
        hsts      = AppConfig.getBoolean("EWF_SECURITY_HSTS", true);
        cspReportOnly = AppConfig.get("EWF_CSP_REPORT_ONLY", null);
        System.out.println("[SecurityHeadersFilter] abilitato=" + abilitato + " hsts=" + hsts
            + (cspReportOnly != null ? " CSP_REPORT_ONLY=" + cspReportOnly : ""));
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (abilitato && res instanceof HttpServletResponse && req instanceof HttpServletRequest) {
            HttpServletRequest  r = (HttpServletRequest) req;
            HttpServletResponse h = (HttpServletResponse) res;
            h.setHeader("X-Content-Type-Options", "nosniff");
            h.setHeader("X-Frame-Options", "SAMEORIGIN");
            h.setHeader("Content-Security-Policy", "frame-ancestors 'self'");
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

    @Override
    public void destroy() { }
}
