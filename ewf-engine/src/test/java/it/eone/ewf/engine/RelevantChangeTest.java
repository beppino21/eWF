package it.eone.ewf.engine;

import it.eone.ewf.engine.model.DocItem;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.ProcessInstance;
import it.eone.ewf.engine.spi.DocumentAdapter.DocumentInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Quando una modifica senza strategia di rilascio fa ripartire il processo: confronto per posizione. */
class RelevantChangeTest {

    private static DocItem i(String key, String qty, String value) {
        return new DocItem(key, new BigDecimal(qty), new BigDecimal(value));
    }

    private static ProcessInstance started(DocItem... items) {
        ProcessInstance p = new ProcessInstance();
        p.value = Money.of("0", "EUR");
        p.docItems = List.of(items);
        return p;
    }

    private static DocumentInfo now(DocItem... items) {
        return DocumentInfo.found(Money.of("0", "EUR"), Map.of(), List.of(items));
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Test
    void variazioniOppostePerDuePosizioniNonSiAnnullano() {
        // totale quantita' 15 -> 15, totale valore 1500 -> 1500: le somme non vedrebbero nulla
        String why = WorkflowEngine.relevantChange(
                started(i("00010", "10", "1000"), i("00020", "5", "500")),
                now(i("00010", "12", "1200"), i("00020", "3", "300")), ZERO);
        assertEquals("posizione 00010 quantita' 10 -> 12", why, "la prima posizione che diverge basta");
    }

    @Test
    void quantitaSpostataTraPosizioni() {
        assertNotNull(WorkflowEngine.relevantChange(
                started(i("00010", "10", "1000"), i("00020", "10", "1000")),
                now(i("00010", "15", "1000"), i("00020", "5", "1000")), ZERO));
    }

    @Test
    void posizioneSostituitaDaUnaUguale() {
        String why = WorkflowEngine.relevantChange(
                started(i("00010", "10", "1000"), i("00020", "5", "500")),
                now(i("00010", "10", "1000"), i("00030", "5", "500")), ZERO);
        assertEquals("posizione 00020 eliminata", why);
    }

    @Test
    void posizioneAggiunta() {
        assertEquals("posizione 00020 aggiunta", WorkflowEngine.relevantChange(
                started(i("00010", "10", "1000")), now(i("00010", "10", "1000"), i("00020", "1", "10")), ZERO));
    }

    @Test
    void nessunaVariazioneNessunRiavvio() {
        assertNull(WorkflowEngine.relevantChange(
                started(i("00010", "10", "1000"), i("00020", "5", "500")),
                now(i("00010", "10.000", "1000.00"), i("00020", "5", "500")), ZERO), "10 e 10.000 sono uguali");
    }

    @Test
    void anchePrezzoSenzaQuantita() {
        assertEquals("posizione 00010 valore 1000 -> 990", WorkflowEngine.relevantChange(
                started(i("00010", "10", "1000")), now(i("00010", "10", "990")), ZERO),
                "anche in diminuzione, come la prassi del vecchio eWF");
    }

    @Test
    void tolleranzaPercentualePerPosizione() {
        BigDecimal five = new BigDecimal("5");
        assertNull(WorkflowEngine.relevantChange(started(i("00010", "100", "1000")),
                now(i("00010", "105", "1050")), five), "5% esatto: dentro");
        assertNotNull(WorkflowEngine.relevantChange(started(i("00010", "100", "1000"), i("00020", "1", "10")),
                now(i("00010", "100", "1000"), i("00020", "2", "10")), five),
                "su una posizione piccola +100%: la tolleranza vale per posizione, non sul totale");
    }

    @Test
    void documentoSenzaPosizioniConfrontaIlValoreDiTestata() {
        ProcessInstance p = new ProcessInstance();
        p.value = Money.of("1000", "EUR");
        assertNull(WorkflowEngine.relevantChange(p, DocumentInfo.found(Money.of("1000.00", "EUR"), Map.of()), ZERO));
        assertEquals("valore 1000 -> 1200", WorkflowEngine.relevantChange(p,
                DocumentInfo.found(Money.of("1200", "EUR"), Map.of()), ZERO));
    }
}
