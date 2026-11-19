package it.eone.ewf.ewfserver.view;

import it.eone.ewf.engine.Requests;
import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.AdminService.ErrorKind;
import it.eone.ewf.service.AdminService.ProcessError;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.format.DateTimeFormatter;

/**
 * Processi in errore, solo per gli amministratori (membri del team di riserva, WFADM):
 * - esito incerto di una chiamata a SAP: l'amministratore verifica in SAP e conferma (con nota obbligatoria);
 * - azione di fine processo non riuscita: si riprova dopo aver tolto la causa (blocco, dato mancante...).
 */
@CCGenClass(expressionBase = "#{d.ErrorsUI}")
public class ErrorsUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public interface IListener extends Serializable {
        /** Il numero dei processi in errore e' cambiato (per il menu). */
        void reactOnCountChanged(int count);
    }

    private final FIXGRIDListBinding<Item> m_grid = new FIXGRIDListBinding<>();
    private IListener m_listener;
    private IBackToMenu m_back;
    private ProcessError m_selected;
    private String m_note;
    private String m_status = "";

    public ErrorsUI() {
        SessionGuard.requireUser("/Errors.xml");
    }

    @Override public String getPageName() { return "/Errors.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.ErrorsUI}"; }

    public void prepare(IListener listener, IBackToMenu back) {
        m_listener = listener;
        m_back = back;
        reload();
    }

    private static AdminService svc() { return EwfApp.get().admin(); }
    private static long me() { return EwfSession.instance().userId(); }

    public void reload() {
        Long keep = m_selected == null ? null : m_selected.processId();
        m_selected = null;
        m_grid.getItems().clear();
        try {
            for (ProcessError e : svc().processesInError(me())) {
                Item i = new Item(e);
                m_grid.getItems().add(i);
                if (keep != null && keep == e.processId()) select(i);
            }
            int n = m_grid.getItems().size();
            m_status = n == 0 ? "Nessun processo in errore" : n + (n == 1 ? " processo in errore" : " processi in errore");
            if (m_listener != null) m_listener.reactOnCountChanged(n);
        } catch (ServiceException e) {
            m_status = "";
            Statusbar.outputError(e.getMessage());
        }
    }

    private void select(Item i) {
        m_grid.selectItem(i);
        if (m_selected == null || m_selected.processId() != i.e.processId()) m_note = null;
        m_selected = i.e;
    }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) {
        if (m_back != null) m_back.backToMenu();
    }

    public void onRefresh(ActionEvent ae) { reload(); }

    public void onConfirmDone(ActionEvent ae) { resolve(true); }
    public void onConfirmNotDone(ActionEvent ae) { resolve(false); }

    private void resolve(boolean succeeded) {
        ProcessError e = m_selected;
        if (e == null || e.executionId() == null) return;
        if (m_note == null || m_note.isBlank()) {
            Statusbar.outputError("Scrivere nella nota cosa e' stato verificato in SAP (es. \"ME23N: OdA rilasciato\")");
            return;
        }
        String q = succeeded
                ? "Confermi che in SAP l'operazione risulta ESEGUITA? Il processo prosegue come se la chiamata fosse andata a buon fine."
                : "Confermi che in SAP l'operazione NON risulta eseguita? L'attivita' torna a chi doveva farla.";
        YESNOPopup.createInstance("Esito della chiamata a SAP", q, new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    Requests.Outcome o = svc().resolveUnknown(me(), e.executionId(), succeeded, m_note);
                    if (o.result() == Requests.Outcome.Result.DONE)
                        Statusbar.outputSuccess("Esito registrato per il processo " + e.processId());
                    else
                        Statusbar.outputWarning("Esito registrato, ma il processo non e' avanzato: " + o.message());
                    m_note = null;
                } catch (ServiceException ex) {
                    Statusbar.outputError(ex.getMessage());
                }
                reload();
            }
            @Override public void reactOnNo() { }
        });
    }

    public void onRetry(ActionEvent ae) {
        ProcessError e = m_selected;
        if (e == null) return;
        try {
            String status = svc().retryCompletion(me(), e.processId());
            if ("COMPLETED".equals(status) || "REJECTED".equals(status) || "CANCELLED".equals(status) || "FORWARDED".equals(status))
                Statusbar.outputSuccess("Processo " + e.processId() + " chiuso");
            else
                Statusbar.outputWarning("Azione finale ancora non riuscita (stato " + status + "): verificare la causa in SAP");
        } catch (ServiceException ex) {
            Statusbar.outputError(ex.getMessage());
        }
        reload();
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<Item> getGrid() { return m_grid; }
    public String getStatus() { return m_status; }
    public boolean isSelected() { return m_selected != null; }
    public boolean isNothingSelected() { return m_selected == null && !m_grid.getItems().isEmpty(); }
    public boolean isUnknownSelected() { return m_selected != null && m_selected.kind() == ErrorKind.UNKNOWN && m_selected.executionId() != null; }
    public boolean isEndFailedSelected() { return m_selected != null && m_selected.kind() == ErrorKind.END_FAILED; }
    public boolean isOtherSelected() { return m_selected != null && !isUnknownSelected() && !isEndFailedSelected(); }
    public String getNote() { return m_note; }
    public void setNote(String v) { m_note = v; }

    public String getSelectedTitle() {
        if (m_selected == null) return "";
        ProcessError e = m_selected;
        return "Processo " + e.processId() + " - " + nz(e.processText(), e.processType()) + " " + nz(e.documentKey(), "");
    }

    public String getSelectedDetail() {
        if (m_selected == null) return "";
        ProcessError e = m_selected;
        StringBuilder b = new StringBuilder();
        b.append(kindText(e.kind()));
        if (e.taskText() != null) b.append("\nAttivita': ").append(e.taskText());
        if (e.handler() != null) b.append("\nChiamata: ").append(e.handler()).append(e.event() == null ? "" : " (" + e.event() + ")");
        if (e.message() != null && !e.message().isBlank()) b.append("\nMessaggio: ").append(e.message());
        if (e.starter() != null) b.append("\nAvviato da: ").append(e.starter());
        return b.toString();
    }

    static String kindText(ErrorKind k) {
        return switch (k) {
            case UNKNOWN -> "Esito incerto di una chiamata a SAP";
            case END_FAILED -> "Azione di fine processo non riuscita";
            default -> "Errore";
        };
    }

    private static String nz(String a, String b) { return a == null || a.isBlank() ? (b == null ? "" : b) : a; }

    // ------------------------------------------------------------------ riga

    public class Item extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final ProcessError e;

        Item(ProcessError e) { this.e = e; }

        @Override public void onRowSelect() { select(this); }

        public String getSince() { return e.since() == null ? "" : WHEN.format(e.since().atZone(EwfApp.get().zone())); }
        public long getSinceSort() { return e.since() == null ? 0 : e.since().toEpochMilli(); }
        public String getProcess() { return nz(e.processText(), e.processType()); }
        public String getDocument() { return nz(e.documentKey(), ""); }
        public String getKind() { return kindText(e.kind()); }
        public String getKindColor() { return e.kind() == ErrorKind.UNKNOWN ? "#FFE0B2" : "#FFCDD2"; }
        public String getTask() { return nz(e.taskText(), ""); }
        public String getMessage() { return nz(e.message(), nz(e.reason(), "")); }
    }
}
