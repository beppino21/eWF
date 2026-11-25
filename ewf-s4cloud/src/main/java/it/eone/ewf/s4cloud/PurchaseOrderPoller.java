package it.eone.ewf.s4cloud;

import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.DocumentEventType;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.Requests.EventResult.EventStatus;
import it.eone.ewf.engine.spi.StartSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sorgente degli eventi per gli OdA di S/4 Public (strada D, D23): non c'e' una coda scritta da SAP, si leggono
 * gli OdA modificati dopo l'ultimo punto di ripresa (LastChangeDateTime) e si traduce lo stato in un evento.
 *
 * <pre>
 *   cancellato (testata o tutte le posizioni)  -> TOUCHED con fotografia "non esiste": si annullano i processi aperti
 *   03 in approvazione                         -> TOUCHED "nuovo": avvio con le regole, o confronto con il processo aperto
 *   05 rilasciato                              -> APPROVED_IN_SOURCE: chiude il task INTERACTIVE (o segnala l'anomalia)
 *   08 respinto                                -> REJECTED_IN_SOURCE: come sopra, con esito negativo
 *   altri stati (in creazione, ...)            -> nessun evento, il punto di ripresa avanza
 * </pre>
 * Il criterio "serve un'approvazione?" resta solo in SAP (Flexible Workflow): eWF parte quando vede lo stato 03.
 *
 * Punto di ripresa: avanza fino all'ultimo OdA elaborato senza errori, nell'ordine di modifica. Un OdA in errore
 * (es. motore non raggiungibile) ferma l'avanzamento e viene riletto al giro dopo con quelli che lo seguono
 * (il motore li tratta in modo idempotente). Dopo "maxAttempts" errori sullo stesso evento il punto di ripresa
 * lo supera: l'errore resta nello storico del job. Il conteggio dei tentativi e' in memoria (si azzera al riavvio).
 */
public final class PurchaseOrderPoller implements StartSource {

    /** Dove tenere il punto di ripresa (in ewfserver: eone_connect.sync_state). */
    public interface Watermark {
        /** Punto di ripresa, null se mai impostato. Inizia il giro. */
        Instant begin();
        /** Giro concluso: il punto di ripresa avanza a "to" (puo' restare uguale). */
        void advance(Instant to, long items, String message);
        /** Giro fallito prima di elaborare: il punto resta dov'era. */
        void failed(String message);
    }

    private static final Map<String, Integer> ATTEMPTS = new ConcurrentHashMap<>();

    private final PurchaseOrderApi api;
    private final Watermark watermark;
    private final String backend;
    private final Instant initial;
    private final int maxAttempts;

    private record Entry(Instant changedAt, String queueId) {}

    private final List<Entry> pending = new ArrayList<>();
    private Instant from;

    /**
     * @param backend codice del sistema (backend_system.code) registrato sui processi; null = sistema unico
     * @param initial punto di partenza la prima volta (tipicamente "adesso": gli OdA precedenti non si ripescano)
     */
    public PurchaseOrderPoller(PurchaseOrderApi api, Watermark watermark, String backend, Instant initial, int maxAttempts) {
        this.api = api;
        this.watermark = watermark;
        this.backend = backend;
        this.initial = initial;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public List<DocumentEvent> fetch(int max) {
        pending.clear();
        Instant wm = watermark.begin();
        from = wm == null ? initial : wm;
        List<PurchaseOrder> changed;
        try {
            changed = api.changedAfter(from, max);
        } catch (RuntimeException e) {
            watermark.failed(e.getMessage());
            throw e;
        }
        List<DocumentEvent> events = new ArrayList<>();
        for (PurchaseOrder po : changed) {
            DocumentEvent e = toEvent(po, backend);
            pending.add(new Entry(po.lastChange(), e == null ? null : e.queueId()));
            if (e != null) events.add(e);
        }
        if (events.isEmpty())
            watermark.advance(changed.isEmpty() ? from : changed.get(changed.size() - 1).lastChange(), 0,
                    changed.isEmpty() ? null : changed.size() + " OdA modificati, nessuno da elaborare");
        return events;
    }

    @Override
    public void confirm(List<EventResult> results) {
        if (pending.isEmpty()) return;
        Map<String, EventResult> byId = new HashMap<>();
        for (EventResult r : results) byId.put(r.queueId(), r);
        Instant to = from;
        long done = 0;
        String stop = null;
        for (Entry p : pending) {
            if (p.queueId() != null) {
                EventResult r = byId.get(p.queueId());
                if (r == null) { stop = p.queueId() + ": rinviato (errore su un evento precedente dello stesso OdA)"; break; }
                if (r.status() == EventStatus.ERROR) {
                    int n = ATTEMPTS.merge(key(p.queueId()), 1, Integer::sum);
                    if (n < maxAttempts) { stop = p.queueId() + ": " + r.message() + " (tentativo " + n + ")"; break; }
                    ATTEMPTS.remove(key(p.queueId()));          // abbandonato: resta nello storico del job
                } else {
                    ATTEMPTS.remove(key(p.queueId()));
                    done++;
                }
            }
            to = p.changedAt();
        }
        watermark.advance(to, done, stop);
        pending.clear();
    }

    private String key(String queueId) { return backend + "|" + queueId; }

    /** Evento per lo stato dell'OdA; null se lo stato non interessa a eWF. */
    static DocumentEvent toEvent(PurchaseOrder po, String backend) {
        String qid = po.number() + "@" + po.lastChange();
        String doc = S4PurchaseOrderAdapter.DOC_TYPE;
        if (po.deleted())
            return new DocumentEvent(qid, doc, po.number(), backend, DocumentEventType.TOUCHED, null, false, po.toDocumentInfo());
        if (po.status() == null) return null;
        return switch (po.status()) {
            case PurchaseOrder.IN_APPROVAL -> new DocumentEvent(qid, doc, po.number(), backend, DocumentEventType.TOUCHED,
                    po.createdBy(), true, po.toDocumentInfo());
            case PurchaseOrder.RELEASED -> new DocumentEvent(qid, doc, po.number(), backend,
                    DocumentEventType.APPROVED_IN_SOURCE, null, false, po.toDocumentInfo());
            case PurchaseOrder.REJECTED -> new DocumentEvent(qid, doc, po.number(), backend,
                    DocumentEventType.REJECTED_IN_SOURCE, null, false, po.toDocumentInfo());
            default -> null;
        };
    }
}
