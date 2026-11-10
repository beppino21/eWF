package it.eone.ewf.engine;

import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.spi.StartSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Legge la coda degli avvii, elabora ogni evento con il motore e conferma gli esiti alla sorgente.
 * Un giro alla volta (runOnce): lo schedulatore (thread, timer dell'applicazione) decide la frequenza.
 * Gli eventi dello stesso documento si elaborano nell'ordine in cui sono nati.
 */
public final class StartQueueProcessor {

    private final WorkflowEngine engine;
    private final StartSource source;
    private final int batchSize;

    public StartQueueProcessor(WorkflowEngine engine, StartSource source, int batchSize) {
        this.engine = engine;
        this.source = source;
        this.batchSize = batchSize;
    }

    /** Elabora fino a batchSize eventi; restituisce gli esiti (gia' confermati alla sorgente). */
    public List<EventResult> runOnce() {
        List<DocumentEvent> events = source.fetch(batchSize);
        if (events.isEmpty()) return List.of();
        List<EventResult> results = new ArrayList<>();
        java.util.Set<String> blocked = new java.util.HashSet<>();
        for (DocumentEvent e : events) {
            String doc = e.docType() + "|" + e.documentKey() + "|" + e.backend();
            // un evento fallito blocca i successivi dello stesso documento: restano in coda, nell'ordine giusto
            if (blocked.contains(doc)) continue;
            EventResult r = engine.onDocumentEvent(e);
            results.add(r);
            if (r.status() == EventResult.EventStatus.ERROR) blocked.add(doc);
        }
        source.confirm(results);
        return results;
    }
}
