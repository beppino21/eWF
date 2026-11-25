package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.app.JobHost;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.Column;
import it.eone.ewf.service.Views.Light;
import it.eone.ewf.service.Views.Reason;
import it.eone.ewf.service.Views.InfoCounts;
import it.eone.ewf.service.Views.WorkItem;
import it.eone.ewf.service.Views.Worklist;
import it.eone.ewf.service.Views.WorklistFilter;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lista delle attivita' dell'utente: proprie, da sostituto, di team. Semaforo dei giorni di attesa, filtro per
 * tipo di processo (con le colonne specifiche del tipo) e ricerca libera. Doppio clic: dettaglio.
 */
@CCGenClass(expressionBase = "#{d.WorklistUI}")
public class WorklistUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    /** Colonne specifiche visibili quando si filtra su un tipo di processo. */
    static final int EXTRA_COLS = 6;

    public interface IListener extends Serializable {
        void reactOnOpen(long processId);
        void reactOnLogout();
        void reactOnMenu();
    }

    private IListener m_listener;
    private final FIXGRIDListBinding<Item> m_grid = new FIXGRIDListBinding<>();
    private String m_status = "";
    private List<Column> m_columns = List.of();
    /** Filtri per colonna (riga sotto le intestazioni, come nel ticketing): chiave = nome della colonna. */
    private final Map<String, String> m_filters = new HashMap<>();
    /** Colonne specifiche per tipo di processo, lette una volta per tipo. */
    private final Map<String, List<Column>> m_columnsByType = new HashMap<>();
    /** Quanto c'e' da vedere per processo (pulsanti Allegati / Commenti / Storico). */
    private Map<Long, InfoCounts> m_counts = Map.of();

    public WorklistUI() {
        SessionGuard.requireUser("/Worklist.xml");
    }

    @Override public String getPageName() { return "/Worklist.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.WorklistUI}"; }

    public void prepare(IListener listener) {
        m_listener = listener;
        reload();
    }

    /** Rilegge dal database e riapplica i filtri per colonna. */
    public void reload() {
        EwfService svc = EwfApp.get().service();
        try {
            Worklist w = svc.worklist(EwfSession.instance().userId(), WorklistFilter.all());
            // 1. filtri sulle colonne fisse
            List<WorkItem> rows = new ArrayList<>();
            for (WorkItem i : w.items()) if (matches(new Item(i), FIXED)) rows.add(i);
            // 2. se resta un solo tipo di processo compaiono le sue colonne, filtrabili anch'esse
            Set<String> types = new HashSet<>();
            for (WorkItem i : rows) types.add(i.processType());
            m_columns = types.size() == 1
                    ? m_columnsByType.computeIfAbsent(types.iterator().next(), svc::columns) : List.of();
            m_grid.getItems().clear();
            List<Long> ids = new ArrayList<>();
            for (WorkItem i : rows) {
                Item it = new Item(i);
                if (!m_columns.isEmpty() && !matches(it, DYNAMIC)) continue;
                m_grid.getItems().add(it);
                ids.add(i.processId());
            }
            m_counts = svc.infoCounts(EwfSession.instance().userId(), ids);
            int total = w.items().size(), shown = m_grid.getItems().size();
            m_status = shown == total ? total + " attivita'" : shown + " di " + total + " attivita'";
        } catch (ServiceException e) {
            m_grid.getItems().clear();
            m_status = "";
            Statusbar.outputError(e.getMessage());
        }
    }

    private static final List<String> FIXED = List.of("days", "process", "document", "summary", "task", "forWhom",
            "starter", "value", "since");
    private static final List<String> DYNAMIC = List.of("col1", "col2", "col3", "col4", "col5", "col6");

    private boolean matches(Item it, List<String> keys) {
        for (String k : keys) {
            String f = m_filters.get(k);
            if (f == null || f.isBlank()) continue;
            String v = switch (k) {
                case "days" -> it.getDays();
                case "process" -> it.getProcessType();
                case "document" -> it.getDocument();
                case "summary" -> it.getSummary();
                case "task" -> it.getTaskText();
                case "forWhom" -> it.getForWhom();
                case "starter" -> it.getStarter();
                case "value" -> it.getValue();
                case "since" -> it.getSince();
                default -> it.col(Integer.parseInt(k.substring(3)) - 1);
            };
            if (v == null || !v.toLowerCase(Locale.ITALY).contains(f.trim().toLowerCase(Locale.ITALY))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ azioni

    /**
     * "Aggiorna": prima fa leggere subito allo scheduler la coda degli avvii da SAP (gli OdA appena
     * creati diventano processi), poi rilegge la lista. L'attesa e' di pochi secondi al massimo.
     */
    public void onBackToMenu(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnMenu();
    }

    public void onRefresh(ActionEvent ae) {
        JobHost.QueueRead r = JobHost.readStartQueueNow(Duration.ofSeconds(6));
        reload();
        switch (r) {
            case FAILED -> Statusbar.outputWarning("Lettura dei nuovi documenti da SAP non riuscita: lista aggiornata con i dati gia' presenti.");
            case STILL_RUNNING -> Statusbar.outputMessage("La lettura dei nuovi documenti da SAP e' ancora in corso: riaggiornare tra qualche secondo.");
            default -> { }
        }
    }
    public void onColFilter(ActionEvent ae) { reload(); }

    public void onResetFilters(ActionEvent ae) {
        m_filters.clear();
        reload();
    }

    public void onLogout(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnLogout();
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<Item> getGrid() { return m_grid; }
    /** Valori dei filtri per colonna (un campo sotto ogni intestazione). */
    public String getFilterDays() { return m_filters.get("days"); }
    public void setFilterDays(String v) { m_filters.put("days", v); }
    public String getFilterProcess() { return m_filters.get("process"); }
    public void setFilterProcess(String v) { m_filters.put("process", v); }
    public String getFilterDocument() { return m_filters.get("document"); }
    public void setFilterDocument(String v) { m_filters.put("document", v); }
    public String getFilterSummary() { return m_filters.get("summary"); }
    public void setFilterSummary(String v) { m_filters.put("summary", v); }
    public String getFilterTask() { return m_filters.get("task"); }
    public void setFilterTask(String v) { m_filters.put("task", v); }
    public String getFilterForWhom() { return m_filters.get("forWhom"); }
    public void setFilterForWhom(String v) { m_filters.put("forWhom", v); }
    public String getFilterStarter() { return m_filters.get("starter"); }
    public void setFilterStarter(String v) { m_filters.put("starter", v); }
    public String getFilterValue() { return m_filters.get("value"); }
    public void setFilterValue(String v) { m_filters.put("value", v); }
    public String getFilterSince() { return m_filters.get("since"); }
    public void setFilterSince(String v) { m_filters.put("since", v); }
    public String getFilterCol1() { return m_filters.get("col1"); }
    public void setFilterCol1(String v) { m_filters.put("col1", v); }
    public String getFilterCol2() { return m_filters.get("col2"); }
    public void setFilterCol2(String v) { m_filters.put("col2", v); }
    public String getFilterCol3() { return m_filters.get("col3"); }
    public void setFilterCol3(String v) { m_filters.put("col3", v); }
    public String getFilterCol4() { return m_filters.get("col4"); }
    public void setFilterCol4(String v) { m_filters.put("col4", v); }
    public String getFilterCol5() { return m_filters.get("col5"); }
    public void setFilterCol5(String v) { m_filters.put("col5", v); }
    public String getFilterCol6() { return m_filters.get("col6"); }
    public void setFilterCol6(String v) { m_filters.put("col6", v); }
    public boolean isFiltered() { return m_filters.values().stream().anyMatch(v -> v != null && !v.isBlank()); }
    public String getStatus() { return m_status; }
    public String getUserLabel() {
        var u = EwfSession.instance().user();
        return u == null ? "" : u.displayName();
    }

    public String getColLabel1() { return colLabel(0); }
    public String getColLabel2() { return colLabel(1); }
    public String getColLabel3() { return colLabel(2); }
    public String getColLabel4() { return colLabel(3); }
    public String getColLabel5() { return colLabel(4); }
    public String getColLabel6() { return colLabel(5); }
    public boolean isCol1() { return m_columns.size() > 0; }
    public boolean isCol2() { return m_columns.size() > 1; }
    public boolean isCol3() { return m_columns.size() > 2; }
    public boolean isCol4() { return m_columns.size() > 3; }
    public boolean isCol5() { return m_columns.size() > 4; }
    public boolean isCol6() { return m_columns.size() > 5; }
    /** Il riepilogo serve nella lista mista; filtrando su un tipo ci sono le sue colonne. */
    public boolean isSummaryVisible() { return m_columns.isEmpty(); }

    private String colLabel(int i) {
        return i < m_columns.size() ? m_columns.get(i).label() : "";
    }

    // ------------------------------------------------------------------ riga

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String HIGHLIGHT = "#BBDEFB";

    /** Storico, Gantt, Commenti e Allegati in una finestra sopra la lista, senza aprire il dettaglio. */
    private void openInfo(WorkItem w, ProcessInfoUI.Mode mode) {
        ProcessInfoUI ui = new ProcessInfoUI();
        ui.prepare(w.processId(), mode, false);
        openModalPopup(ui, w.processTypeText() + " " + w.documentKey(), 1000, 600, new ModalPopup.IModalPopupListener() {
            @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
        });
    }

    public class Item extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final WorkItem w;

        Item(WorkItem w) { this.w = w; }

        @Override public void onRowExecute() {
            if (m_listener != null) m_listener.reactOnOpen(w.processId());
        }

        public void onAttachments(ActionEvent ae) { openInfo(w, ProcessInfoUI.Mode.ATTACHMENTS); }
        public void onHistory(ActionEvent ae) { openInfo(w, ProcessInfoUI.Mode.HISTORY); }
        public void onComments(ActionEvent ae) { openInfo(w, ProcessInfoUI.Mode.COMMENTS); }

        public void onOpen(ActionEvent ae) { onRowExecute(); }

        private InfoCounts counts() { return m_counts.getOrDefault(w.processId(), InfoCounts.NONE); }
        /** -1 = allegati nel sistema di origine: non si sanno contare senza chiamarlo, il pulsante resta attivo. */
        public boolean isAttachmentsEnabled() { return counts().attachments() != 0; }
        public boolean isCommentsEnabled() { return counts().comments() > 0; }
        public boolean isHistoryEnabled() { return counts().history() > 0; }
        public String getAttachmentsText() { int n = counts().attachments(); return n > 0 ? "Allegati (" + n + ")" : "Allegati"; }
        public String getCommentsText() { int n = counts().comments(); return n > 0 ? "Commenti (" + n + ")" : "Commenti"; }
        public String getHistoryText() { int n = counts().history(); return n > 0 ? "Storico (" + n + ")" : "Storico"; }
        public String getAttachmentsBg() { return counts().attachments() > 0 ? HIGHLIGHT : null; }
        public String getCommentsBg() { return counts().comments() > 0 ? HIGHLIGHT : null; }
        public String getHistoryBg() { return counts().history() > 0 ? HIGHLIGHT : null; }

        public String getDays() { return String.valueOf(w.daysWaiting()); }
        public int getDaysSort() { return w.daysWaiting(); }
        public String getLightColor() {
            return w.light() == Light.RED ? "#E57373" : w.light() == Light.YELLOW ? "#FFD54F" : "#81C784";
        }
        public String getProcessType() { return w.processTypeText(); }
        public String getDocument() { return w.documentKey(); }
        public String getSummary() { return w.summary() == null ? "" : w.summary(); }
        public String getTaskText() { return w.taskText() + (w.webActionable() ? "" : " (in SAP)"); }
        public String getForWhom() {
            return w.reason() == Reason.SUBSTITUTE ? "per " + w.forWhom()
                    : w.reason() == Reason.TEAM ? "team " + w.forWhom() : "";
        }
        public String getForWhomBackground() { return w.reason() == Reason.SUBSTITUTE ? "#FFF59D" : null; }
        public String getStarter() { return w.starter() == null ? "" : w.starter(); }
        public String getValue() {
            return w.value() == null ? "" : EwfService.formatAmount(w.value().amount()) + " " + w.value().currency();
        }
        public java.math.BigDecimal getValueSort() { return w.value() == null ? null : w.value().amount(); }
        public String getSince() { return DAY.format(w.waitingSince().atZone(EwfApp.get().zone())); }
        public long getSinceSort() { return w.waitingSince().toEpochMilli(); }

        public String getCol1() { return col(0); }
        public String getCol2() { return col(1); }
        public String getCol3() { return col(2); }
        public String getCol4() { return col(3); }
        public String getCol5() { return col(4); }
        public String getCol6() { return col(5); }

        String col(int i) {
            if (i >= m_columns.size()) return "";
            String v = w.attributes().get(m_columns.get(i).key());
            return v == null ? "" : v;
        }
    }
}
