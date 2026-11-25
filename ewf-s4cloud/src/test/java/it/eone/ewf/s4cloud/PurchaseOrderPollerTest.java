package it.eone.ewf.s4cloud;

import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.DocumentEventType;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.Requests.EventResult.EventStatus;
import it.eone.ewf.engine.spi.DocumentAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Polling senza S/4: il trasporto restituisce le risposte salvate, il punto di ripresa sta in memoria. */
class PurchaseOrderPollerTest {

    static final Instant START = Instant.parse("2026-11-25T13:00:00Z");

    final List<String> urls = new ArrayList<>();
    String body = PurchaseOrderTest.resource("po_changed.json");

    final PurchaseOrderApi api = new PurchaseOrderApi(new S4Connection("DEV", "https://my434383-api.s4hana.cloud.sap/",
            url -> { urls.add(url); return body; }));

    static final class MemWatermark implements PurchaseOrderPoller.Watermark {
        Instant value;
        String lastMessage;
        int failures;
        @Override public Instant begin() { return value; }
        @Override public void advance(Instant to, long items, String message) { value = to; lastMessage = message; }
        @Override public void failed(String message) { failures++; lastMessage = message; }
    }

    final MemWatermark wm = new MemWatermark();

    PurchaseOrderPoller poller(int attempts) {
        return new PurchaseOrderPoller(api, wm, "S4PUB", START, attempts);
    }

    static EventResult result(DocumentEvent e, EventStatus s) {
        return new EventResult(e.queueId(), s, s.name(), null);
    }

    @Test
    void laPrimaVoltaParteDalPuntoInizialeEFiltraSulleModifiche() {
        poller(3).fetch(50);
        String url = urls.get(0);
        assertTrue(url.startsWith("https://my434383-api.s4hana.cloud.sap/sap/opu/odata4/sap/api_purchaseorder_2/"), url);
        assertTrue(url.contains("$filter=LastChangeDateTime%20gt%202026-11-25T13%3A00%3A00Z"), url);
        assertTrue(url.contains("$top=50"), url);
        assertTrue(url.contains("PurchasingDocumentDeletionCode"), "serve per escludere le posizioni cancellate");
        assertFalse(url.contains("+"), url);
    }

    @Test
    void soloGliStatiCheContanoDiventanoEventi() {
        List<DocumentEvent> ev = poller(3).fetch(50);
        assertEquals(2, ev.size(), "03 e 05; lo 02 non interessa");
        assertEquals(DocumentEventType.TOUCHED, ev.get(0).type());
        assertEquals(DocumentEventType.APPROVED_IN_SOURCE, ev.get(1).type());
    }

    @Test
    void tuttoElaboratoIlPuntoDiRipresaVaAllUltimaModifica() {
        PurchaseOrderPoller p = poller(3);
        List<DocumentEvent> ev = p.fetch(50);
        p.confirm(List.of(result(ev.get(0), EventStatus.DONE), result(ev.get(1), EventStatus.WARNING)));
        assertEquals(Instant.parse("2026-11-25T14:00:02Z"), wm.value);
        assertNull(wm.lastMessage);
    }

    @Test
    void unErroreFermaIlPuntoDiRipresaPrimaDiLuiFinoAlMassimoDeiTentativi() {
        wm.value = Instant.parse("2026-11-25T13:30:00Z");
        for (int n = 1; n <= 2; n++) {
            PurchaseOrderPoller p = poller(3);
            List<DocumentEvent> ev = p.fetch(50);
            p.confirm(List.of(result(ev.get(0), EventStatus.DONE), result(ev.get(1), EventStatus.ERROR)));
            assertEquals(Instant.parse("2026-11-25T14:00:01Z"), wm.value, "avanza fino allo 02, si ferma prima del 05");
            assertTrue(wm.lastMessage.contains("tentativo " + n), wm.lastMessage);
            wm.value = Instant.parse("2026-11-25T13:30:00Z");       // il finto S/4 rimanda sempre le stesse righe
        }
        PurchaseOrderPoller p = poller(3);
        List<DocumentEvent> ev = p.fetch(50);
        p.confirm(List.of(result(ev.get(0), EventStatus.DONE), result(ev.get(1), EventStatus.ERROR)));
        assertEquals(Instant.parse("2026-11-25T14:00:02Z"), wm.value, "terzo errore: l'evento si supera");
    }

    @Test
    void nessunaModificaIlPuntoResta() {
        body = "{\"value\":[]}";
        assertTrue(poller(3).fetch(50).isEmpty());
        assertEquals(START, wm.value, "prima volta: si fissa il punto iniziale");
    }

    @Test
    void s4NonRaggiungibileIlGiroFallisceSenzaMuovereIlPunto() {
        PurchaseOrderApi down = new PurchaseOrderApi(new S4Connection("DEV", "https://x", url -> {
            throw new java.io.IOException("connessione rifiutata");
        }));
        wm.value = START;
        PurchaseOrderPoller p = new PurchaseOrderPoller(down, wm, null, START, 3);
        assertThrows(IllegalStateException.class, () -> p.fetch(10));
        assertEquals(1, wm.failures);
        assertEquals(START, wm.value);
    }

    @Test
    void adattatoreOdaInesistenteENonRaggiungibile() {
        S4PurchaseOrderAdapter notFound = new S4PurchaseOrderAdapter(new PurchaseOrderApi(new S4Connection("DEV", "https://x",
                url -> { throw new S4Connection.NotFound("PurchaseOrder non trovato"); })));
        assertFalse(notFound.read("4599999999").exists());
        S4PurchaseOrderAdapter down = new S4PurchaseOrderAdapter(new PurchaseOrderApi(new S4Connection("DEV", "https://x",
                url -> { throw new java.io.IOException("timeout"); })));
        assertThrows(DocumentAdapter.UnavailableException.class, () -> down.read("4500000190"));
        S4PurchaseOrderAdapter ok = new S4PurchaseOrderAdapter(new PurchaseOrderApi(new S4Connection("DEV", "https://x",
                url -> PurchaseOrderTest.resource("po_4500000190_03.json"))));
        assertTrue(ok.read("4500000190").exists());
    }

    @Test
    void erroreODataLeggibile() {
        assertEquals("/IWBEP/CM_V4_RUNTIME/021 No authorization to access service group",
                S4Connection.odataError("{\"error\":{\"code\":\"/IWBEP/CM_V4_RUNTIME/021\",\"message\":\"No authorization to access service group\"}}"));
    }
}
