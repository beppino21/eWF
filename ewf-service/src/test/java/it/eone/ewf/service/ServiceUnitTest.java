package it.eone.ewf.service;

import it.eone.ewf.service.Views.Light;
import it.eone.ewf.service.auth.PasswordHasher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parti senza database: riepilogo, semaforo, nomi file, importi, hash delle password. */
public class ServiceUnitTest {

    @Test
    public void riepilogoConSegnapostiMancanti() {
        Map<String, String> v = Map.of("sap.LIFNR", "100123", "supplier_name", "Acme Spa", "plant", "1000");
        assertEquals("100123 Acme Spa - 1000", SummaryTemplate.render("{sap.LIFNR} {supplier_name} - {plant} {plant_name}", v));
        assertEquals("1000", SummaryTemplate.render("{nessuno} - {plant}", v), "separatore iniziale orfano");
        assertEquals("Acme Spa", SummaryTemplate.render("{supplier_name}, {x} / {y}", v), "separatori finali orfani");
        assertEquals("100123 - 1000", SummaryTemplate.render("{sap.LIFNR} - {x} - {plant}", v), "separatori doppi");
        assertEquals("Rossi-Bianchi srl", SummaryTemplate.render("{n}", Map.of("n", "Rossi-Bianchi srl")), "trattini nelle parole restano");
        assertNull(SummaryTemplate.render("{x} - {y}", v));
        assertNull(SummaryTemplate.render(null, v));
        assertEquals("costo $1 {", SummaryTemplate.render("costo {p} {", Map.of("p", "$1")), "valori con caratteri speciali");
    }

    @Test
    public void semaforoConSoglieDefault() {
        assertEquals(Light.GREEN, EwfService.light(0, 6, 11));
        assertEquals(Light.GREEN, EwfService.light(5, 6, 11));
        assertEquals(Light.YELLOW, EwfService.light(6, 6, 11));
        assertEquals(Light.YELLOW, EwfService.light(10, 6, 11));
        assertEquals(Light.RED, EwfService.light(11, 6, 11));
    }

    @Test
    public void nomiFileSicuri() {
        assertEquals("passwd", EwfService.safeFileName("../../etc/passwd"));
        assertEquals("offerta.pdf", EwfService.safeFileName("C:\\Users\\x\\offerta.pdf"));
        assertEquals("htaccess", EwfService.safeFileName(".htaccess"));
        assertEquals("a_b_.txt", EwfService.safeFileName("a\u0000b?.txt"));
        assertEquals("allegato", EwfService.safeFileName("  "));
        assertEquals("allegato", EwfService.safeFileName(null));
        String longName = "x".repeat(300) + ".pdf";
        String safe = EwfService.safeFileName(longName);
        assertEquals(255, safe.length());
        assertTrue(safe.endsWith(".pdf"), "l'estensione resta");
    }

    @Test
    public void importiAllItaliana() {
        assertEquals("1.500,50", EwfService.formatAmount(new BigDecimal("1500.5")));
        assertEquals("0,00", EwfService.formatAmount(BigDecimal.ZERO));
        assertEquals("-12.345.678,90", EwfService.formatAmount(new BigDecimal("-12345678.9")));
    }

    @Test
    public void hashPasswordVerificaERiHash() {
        PasswordHasher h = new PasswordHasher(1000);
        String a = h.hash("Primavera2026".toCharArray());
        String b = h.hash("Primavera2026".toCharArray());
        assertNotEquals(a, b, "sale diverso ogni volta");
        assertTrue(a.startsWith("pbkdf2-sha256$1000$"));
        assertTrue(h.verify("Primavera2026".toCharArray(), a));
        assertFalse(h.verify("primavera2026".toCharArray(), a));
        assertFalse(h.verify("x".toCharArray(), null));
        assertFalse(h.verify("x".toCharArray(), "md5$abc"));
        assertFalse(h.verify("x".toCharArray(), "pbkdf2-sha256$1000$%%%$%%%"));
        assertFalse(h.needsRehash(a));
        assertTrue(new PasswordHasher(2000).needsRehash(a), "piu' iterazioni: rifare l'hash al prossimo login");
        assertTrue(new PasswordHasher(2000).verify("Primavera2026".toCharArray(), a), "le vecchie restano valide");
    }
}
