package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.EventResult;

import java.util.List;

/**
 * Coda degli avvii nel sistema di origine (on-premise: /EWF/START_Q scritta dalle exit nella LUW del
 * documento; Public: scritta dall'handler locale degli eventi di business). eWF legge, elabora, conferma.
 */
public interface StartSource {
    /** Eventi da elaborare, nell'ordine in cui sono nati (nuovi e in errore da ritentare). */
    List<DocumentEvent> fetch(int max);

    /** Esito di ogni evento: la sorgente lo registra (fatto, fatto con avviso, errore da ritentare). */
    void confirm(List<EventResult> results);
}
