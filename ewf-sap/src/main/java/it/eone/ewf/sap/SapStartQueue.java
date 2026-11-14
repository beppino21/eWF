package it.eone.ewf.sap;

import com.sap.conn.jco.*;
import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.DocumentEventType;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.model.DocItem;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.DocumentAdapter.DocumentInfo;
import it.eone.ewf.engine.spi.StartSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coda degli avvii on-premise: /EWF/START_Q, scritta dalle exit di salvataggio nella LUW del documento.
 * Lettura con /EWF/START_Q_READ, esiti con /EWF/START_Q_CONFIRM (D, W, E).
 * Una riga con evento sconosciuto viene confermata in errore, cosi' resta visibile e non blocca la coda.
 *
 * Fotografia del documento: se la riga la porta (campo EXIST in testata, attributi in ET_ATTR, posizioni in
 * ET_ITEMS), l'evento la consegna al motore e non serve rileggere SAP. Gli attributi passano dalla stessa
 * traduzione di /EWF/DOC_INFO (SapDocumentAdapter.ruleContext): le regole vedono gli stessi nomi in entrambi i casi.
 * Righe del formato precedente (senza EXIST): nessuna fotografia, il motore legge con l'adapter.
 */
public final class SapStartQueue implements StartSource {

    private final String destinationName;
    private final int maxAttempts;

    public SapStartQueue(String destinationName, int maxAttempts) {
        this.destinationName = destinationName;
        this.maxAttempts = maxAttempts;
    }

    private final List<EventResult> rejected = new ArrayList<>();

    @Override
    public List<DocumentEvent> fetch(int max) {
        JCoFunction f = function("/EWF/START_Q_READ");
        f.getImportParameterList().setValue("I_MAX", String.valueOf(max));
        f.getImportParameterList().setValue("I_MAX_ATTEMPTS", String.valueOf(maxAttempts));
        execute(f);
        JCoTable t = f.getTableParameterList().getTable("ET_QUEUE");
        boolean photos = t.getMetaData().hasField("EXIST");
        Map<String, Map<String, String>> attrs = photos ? attributes(f) : Map.of();
        Map<String, List<DocItem>> items = photos ? items(f) : Map.of();
        List<DocumentEvent> out = new ArrayList<>();
        rejected.clear();
        for (int i = 0; i < t.getNumRows(); i++) {
            t.setRow(i);
            String qid = t.getString("QID");
            String event = t.getString("EVENT").trim();
            DocumentEventType type;
            try {
                type = DocumentEventType.valueOf(event);
            } catch (IllegalArgumentException e) {
                rejected.add(new EventResult(qid, EventResult.EventStatus.ERROR, "Evento sconosciuto: " + event, null));
                continue;
            }
            String user = t.getString("UNAME").trim();
            String docType = t.getString("TIPODOC").trim();
            boolean isNew = false;
            DocumentInfo snapshot = null;
            if (photos) {
                isNew = "X".equals(t.getString("IS_NEW").trim());
                boolean exists = "X".equals(t.getString("EXIST").trim());
                String waers = t.getString("WAERS").trim();
                BigDecimal netwr = t.getBigDecimal("NETWR");
                Money value = waers.isEmpty() || netwr == null ? null : new Money(netwr, waers);
                snapshot = new DocumentInfo(exists, value,
                        SapDocumentAdapter.ruleContext(docType, attrs.getOrDefault(qid, Map.of())),
                        exists ? null : "Documento cancellato o senza posizioni attive",
                        items.getOrDefault(qid, List.of()));
            }
            out.add(new DocumentEvent(qid, docType, t.getString("DOCUMENT").trim(), null,
                    type, user.isEmpty() ? null : user, isNew, snapshot));
        }
        if (out.isEmpty() && !rejected.isEmpty()) confirm(List.of());
        return out;
    }

    /** ET_ATTR: QID, NAME, VALUE -> per evento, nome SAP -> valore. */
    private static Map<String, Map<String, String>> attributes(JCoFunction f) {
        Map<String, Map<String, String>> out = new HashMap<>();
        if (!f.getTableParameterList().getMetaData().hasField("ET_ATTR")) return out;
        JCoTable a = f.getTableParameterList().getTable("ET_ATTR");
        for (int i = 0; i < a.getNumRows(); i++) {
            a.setRow(i);
            String name = a.getString("NAME").trim(), value = a.getString("VALUE").trim();
            if (!name.isEmpty() && !value.isEmpty())
                out.computeIfAbsent(a.getString("QID"), k -> new LinkedHashMap<>()).put(name, value);
        }
        return out;
    }

    /** ET_ITEMS: QID, ITEM, MENGE, NETWR -> per evento, posizioni nell'ordine di SAP. */
    private static Map<String, List<DocItem>> items(JCoFunction f) {
        Map<String, List<DocItem>> out = new HashMap<>();
        if (!f.getTableParameterList().getMetaData().hasField("ET_ITEMS")) return out;
        JCoTable it = f.getTableParameterList().getTable("ET_ITEMS");
        for (int i = 0; i < it.getNumRows(); i++) {
            it.setRow(i);
            out.computeIfAbsent(it.getString("QID"), k -> new ArrayList<>())
                    .add(new DocItem(it.getString("ITEM").trim(), it.getBigDecimal("MENGE"), it.getBigDecimal("NETWR")));
        }
        return out;
    }

    @Override
    public void confirm(List<EventResult> results) {
        List<EventResult> all = new ArrayList<>(rejected);
        all.addAll(results);
        rejected.clear();
        if (all.isEmpty()) return;
        JCoFunction f = function("/EWF/START_Q_CONFIRM");
        JCoTable t = f.getTableParameterList().getTable("IT_RESULT");
        for (EventResult r : all) {
            t.appendRow();
            t.setValue("QID", r.queueId());
            t.setValue("STATUS", switch (r.status()) {
                case DONE -> "D";
                case WARNING -> "W";
                case ERROR -> "E";
            });
            String msg = r.message() == null ? "" : r.message();
            t.setValue("MESSAGE", msg.length() > 255 ? msg.substring(0, 255) : msg);
            t.setValue("PROCID", r.processId() == null ? "" : String.valueOf(r.processId()));
        }
        execute(f);
    }

    private JCoFunction function(String name) {
        try {
            JCoFunction f = JCoDestinationManager.getDestination(destinationName).getRepository().getFunction(name);
            if (f == null) throw new IllegalStateException(name + " non trovata nel sistema " + destinationName);
            return f;
        } catch (JCoException e) {
            throw new IllegalStateException("SAP non raggiungibile (" + destinationName + "): " + e.getMessage(), e);
        }
    }

    private void execute(JCoFunction f) {
        try {
            f.execute(JCoDestinationManager.getDestination(destinationName));
        } catch (JCoException e) {
            throw new IllegalStateException(f.getName() + ": " + e.getMessage(), e);
        }
    }
}
