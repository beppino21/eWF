package it.eone.ewf.sap;

import com.sap.conn.jco.*;
import it.eone.ewf.engine.Requests.DocumentEvent;
import it.eone.ewf.engine.Requests.DocumentEventType;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.spi.StartSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Coda degli avvii on-premise: /EWF/START_Q, scritta dalle exit di salvataggio nella LUW del documento.
 * Lettura con /EWF/START_Q_READ, esiti con /EWF/START_Q_CONFIRM (D, W, E).
 * Una riga con evento sconosciuto viene confermata in errore, cosi' resta visibile e non blocca la coda.
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
            out.add(new DocumentEvent(qid, t.getString("TIPODOC").trim(), t.getString("DOCUMENT").trim(), null,
                    type, user.isEmpty() ? null : user));
        }
        if (out.isEmpty() && !rejected.isEmpty()) confirm(List.of());
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
