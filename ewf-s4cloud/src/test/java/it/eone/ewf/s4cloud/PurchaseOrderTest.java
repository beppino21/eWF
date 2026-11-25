package it.eone.ewf.s4cloud;

import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.DocumentEventType;
import it.eone.ewf.engine.spi.DocumentAdapter.DocumentInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Lettura dell'OdA dal JSON reale del tenant di sviluppo (4500000190 dopo la modifica, stato 03). */
class PurchaseOrderTest {

    static String resource(String name) {
        try (InputStream in = PurchaseOrderTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("manca " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static PurchaseOrder po190() {
        return PurchaseOrder.fromJson(Json.object(resource("po_4500000190_03.json")));
    }

    @Test
    void testataEPosizioniDalJsonDelTenant() {
        PurchaseOrder po = po190();
        assertEquals("4500000190", po.number());
        assertEquals(PurchaseOrder.IN_APPROVAL, po.status());
        assertTrue(po.releaseNotCompleted());
        assertEquals(Instant.parse("2026-11-25T14:04:04.970143Z"), po.lastChange(), "microsecondi conservati");
        assertFalse(po.deleted());
        assertEquals(1, po.items().size());
        assertEquals(0, new BigDecimal("1100").compareTo(po.items().get(0).quantity()));
    }

    @Test
    void valoreSommaDellePosizioniEAttributiConINomiDellOnPremise() {
        DocumentInfo d = po190().toDocumentInfo();
        assertTrue(d.exists());
        assertEquals(0, new BigDecimal("550000").compareTo(d.value().amount()));
        assertEquals("EUR", d.value().currency());
        assertEquals("002", d.context().get("purchasing_group"));
        assertEquals("PL01", d.context().get("plant"));
        assertEquals("S001", d.context().get("company_code"));
        assertEquals("NB", d.context().get("sap.BSART"), "stessa chiave delle regole on-premise");
        assertEquals("S4_PUBLIC", d.context().get("backend_kind"), "per una regola di avvio solo S/4 Public");
        assertEquals(1, d.items().size());
        assertEquals("10", d.items().get(0).key());
    }

    @Test
    void posizioniCancellateFuoriDalValoreTutteCancellateOdaCancellato() {
        PurchaseOrder.Item a = new PurchaseOrder.Item("10", "A", null, null, "PL01", BigDecimal.ONE, "KG", new BigDecimal("100"), "");
        PurchaseOrder.Item b = new PurchaseOrder.Item("20", "B", null, null, "PL02", BigDecimal.ONE, "KG", new BigDecimal("900"), "L");
        PurchaseOrder po = new PurchaseOrder("1", "NB", "03", true, "", Instant.EPOCH, null, "S001", null, "002", null, "EUR", List.of(a, b));
        assertEquals(0, new BigDecimal("100").compareTo(po.value().amount()));
        assertEquals("PL01", po.context().get("plant"));
        PurchaseOrder gone = new PurchaseOrder("1", "NB", "03", true, "", Instant.EPOCH, null, "S001", null, "002", null, "EUR", List.of(b));
        assertTrue(gone.deleted());
        assertFalse(gone.toDocumentInfo().exists());
        PurchaseOrder header = new PurchaseOrder("1", "NB", "05", false, "L", Instant.EPOCH, null, null, null, null, null, "EUR", List.of(a));
        assertTrue(header.deleted());
    }

    @Test
    void statoTradottoInEvento() {
        PurchaseOrder po = po190();
        DocumentEvent e = PurchaseOrderPoller.toEvent(po, "S4PUB");
        assertEquals(DocumentEventType.TOUCHED, e.type());
        assertTrue(e.isNew(), "03: avvio con le regole o confronto con il processo aperto");
        assertEquals("CB9980000033", e.sapUser());
        assertEquals("S4PUB", e.backend());
        assertEquals("4500000190@2026-11-25T14:04:04.970143Z", e.queueId());
        assertNotNull(e.snapshot());

        assertEquals(DocumentEventType.APPROVED_IN_SOURCE, PurchaseOrderPoller.toEvent(with(po, "05"), null).type());
        assertEquals(DocumentEventType.REJECTED_IN_SOURCE, PurchaseOrderPoller.toEvent(with(po, "08"), null).type());
        assertNull(PurchaseOrderPoller.toEvent(with(po, "02"), null), "altri stati: nessun evento");

        PurchaseOrder del = new PurchaseOrder(po.number(), po.type(), "05", false, "L", po.lastChange(), null,
                null, null, null, null, "EUR", po.items());
        DocumentEvent d = PurchaseOrderPoller.toEvent(del, null);
        assertEquals(DocumentEventType.TOUCHED, d.type());
        assertFalse(d.snapshot().exists(), "cancellato: il motore annulla i processi aperti");
    }

    @Test
    void jsonMinimo() {
        Map<String, Object> m = Json.object("{\"a\":[1,2.50,-3e2],\"b\":\"x\\u00e8\\\"y\",\"c\":null,\"d\":true,\"e\":{}}");
        assertEquals(List.of(new BigDecimal("1"), new BigDecimal("2.50"), new BigDecimal("-3e2")), m.get("a"));
        assertEquals("xè\"y", m.get("b"));
        assertNull(m.get("c"));
        assertEquals(Boolean.TRUE, m.get("d"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"a\":1,}"));
    }

    private static PurchaseOrder with(PurchaseOrder p, String status) {
        return new PurchaseOrder(p.number(), p.type(), status, p.releaseNotCompleted(), p.deletionCode(), p.lastChange(),
                p.createdBy(), p.companyCode(), p.purchasingOrganization(), p.purchasingGroup(), p.supplier(),
                p.currency(), p.items());
    }
}
