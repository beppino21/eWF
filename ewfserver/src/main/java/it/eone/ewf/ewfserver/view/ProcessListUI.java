package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.InfoCounts;
import it.eone.ewf.service.Views.ParticipatedProcess;
import it.eone.ewf.service.Views.ParticipationFilter;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Elenco di processi in sola consultazione, in due versioni:
 *  - PARTICIPATED: "Processi a cui ho partecipato" (tutti gli utenti);
 *  - ADMIN: "Gestione processi" (amministratori): tutti i processi, ricerca, annullamento di quelli in corso.
 * In riga Allegati / Commenti / Storico (sola lettura) e "Apri" per il dettaglio senza azioni.
 */
@CCGenClass(expressionBase = "#{d.ProcessListUI}")
public class ProcessListUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String HIGHLIGHT = "#BBDEFB";

    public enum Mode { PARTICIPATED, ADMIN }

    public interface IListener extends Serializable {
        void reactOnOpen(long processId);
        void backToMenu();
    }

    private Mode m_mode = Mode.PARTICIPATED;
    private IListener m_listener;
    private ParticipationFilter m_filter = ParticipationFilter.RUNNING;
    private String m_search;
    private String m_status = "";
    private final FIXGRIDListBinding<Item> m_grid = new FIXGRIDListBinding<>();
    private final Map<String, String> m_filters = new HashMap<>();
    private Map<Long, InfoCounts> m_counts = Map.of();

    public ProcessListUI() {
        SessionGuard.requireUser("/ProcessList.xml");
    }

    @Override public String getPageName() { return "/ProcessList.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.ProcessListUI}"; }

    public void prepare(Mode mode, IListener listener) {
        m_mode = mode;
        m_listener = listener;
        reload();
    }

    private static long uid() { return EwfSession.instance().userId(); }

    public void reload() {
        EwfService svc = EwfApp.get().service();
        try {
            List<ParticipatedProcess> rows = m_mode == Mode.ADMIN
                    ? EwfApp.get().admin().processes(uid(), m_filter, m_search)
                    : svc.participated(uid(), m_filter);
            m_grid.getItems().clear();
            List<Long> ids = new ArrayList<>();
            for (ParticipatedProcess p : rows) {
                Item it = new Item(p);
                if (!matches(it)) continue;
                m_grid.getItems().add(it);
                ids.add(p.processId());
            }
            m_counts = svc.infoCounts(uid(), ids);
            int shown = m_grid.getItems().size();
            m_status = (shown == rows.size() ? shown + " processi" : shown + " di " + rows.size() + " processi")
                    + (rows.size() >= 500 ? " (i 500 piu' recenti)" : "");
        } catch (ServiceException e) {
            m_grid.getItems().clear();
            m_status = "";
            Statusbar.outputError(e.getMessage());
        }
    }

    private boolean matches(Item it) {
        for (Map.Entry<String, String> f : m_filters.entrySet()) {
            if (f.getValue() == null || f.getValue().isBlank()) continue;
            String v = switch (f.getKey()) {
                case "process" -> it.getProcess();
                case "document" -> it.getDocument();
                case "summary" -> it.getSummary();
                case "state" -> it.getState();
                case "started" -> it.getStarted();
                case "ended" -> it.getEnded();
                case "starter" -> it.getStarter();
                case "role" -> it.getRole();
                case "where" -> it.getWhere();
                default -> "";
            };
            if (v == null || !v.toLowerCase(Locale.ITALY).contains(f.getValue().trim().toLowerCase(Locale.ITALY))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) { if (m_listener != null) m_listener.backToMenu(); }
    public void onRunning(ActionEvent ae) { m_filter = ParticipationFilter.RUNNING; reload(); }
    public void onClosed(ActionEvent ae) { m_filter = ParticipationFilter.CLOSED; reload(); }
    public void onAll(ActionEvent ae) { m_filter = ParticipationFilter.ALL; reload(); }
    public void onSearch(ActionEvent ae) { reload(); }
    public void onRefresh(ActionEvent ae) { reload(); }
    public void onColFilter(ActionEvent ae) { reload(); }
    public void onResetFilters(ActionEvent ae) { m_filters.clear(); m_search = null; reload(); }

    private void openInfo(ParticipatedProcess p, ProcessInfoUI.Mode mode) {
        ProcessInfoUI ui = new ProcessInfoUI();
        ui.prepare(p.processId(), mode, true);
        openModalPopup(ui, p.processTypeText() + " " + p.documentKey(), 1000, 600, new ModalPopup.IModalPopupListener() {
            @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
        });
    }

    private void openCancel(ParticipatedProcess p) {
        CancelProcessUI ui = new CancelProcessUI();
        ui.prepare(p.processId(), p.processTypeText() + " " + p.documentKey(), done -> {
            closePopup(ui);
            if (done) reload();
        });
        openModalPopup(ui, "Annulla processo", 520, 300, new ModalPopup.IModalPopupListener() {
            @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
        });
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<Item> getGrid() { return m_grid; }
    public String getStatus() { return m_status; }
    public String getTitle() { return m_mode == Mode.ADMIN ? "Gestione processi" : "Processi a cui ho partecipato"; }
    public boolean isAdminMode() { return m_mode == Mode.ADMIN; }
    public boolean isUserMode() { return m_mode == Mode.PARTICIPATED; }
    public String getRunningBg() { return m_filter == ParticipationFilter.RUNNING ? HIGHLIGHT : null; }
    public String getClosedBg() { return m_filter == ParticipationFilter.CLOSED ? HIGHLIGHT : null; }
    public String getAllBg() { return m_filter == ParticipationFilter.ALL ? HIGHLIGHT : null; }
    public String getSearch() { return m_search; }
    public void setSearch(String v) { m_search = v; }
    public boolean isFiltered() {
        return (m_search != null && !m_search.isBlank()) || m_filters.values().stream().anyMatch(v -> v != null && !v.isBlank());
    }

    public String getFilterProcess() { return m_filters.get("process"); }
    public void setFilterProcess(String v) { m_filters.put("process", v); }
    public String getFilterDocument() { return m_filters.get("document"); }
    public void setFilterDocument(String v) { m_filters.put("document", v); }
    public String getFilterSummary() { return m_filters.get("summary"); }
    public void setFilterSummary(String v) { m_filters.put("summary", v); }
    public String getFilterState() { return m_filters.get("state"); }
    public void setFilterState(String v) { m_filters.put("state", v); }
    public String getFilterStarted() { return m_filters.get("started"); }
    public void setFilterStarted(String v) { m_filters.put("started", v); }
    public String getFilterEnded() { return m_filters.get("ended"); }
    public void setFilterEnded(String v) { m_filters.put("ended", v); }
    public String getFilterStarter() { return m_filters.get("starter"); }
    public void setFilterStarter(String v) { m_filters.put("starter", v); }
    public String getFilterRole() { return m_filters.get("role"); }
    public void setFilterRole(String v) { m_filters.put("role", v); }
    public String getFilterWhere() { return m_filters.get("where"); }
    public void setFilterWhere(String v) { m_filters.put("where", v); }

    static String stateText(String status) {
        return switch (status == null ? "" : status) {
            case "RUNNING" -> "In corso";
            case "SUSPENDED" -> "Sospeso";
            case "COMPLETING" -> "In chiusura";
            case "ERROR" -> "In errore";
            case "COMPLETED" -> "Approvato";
            case "REJECTED" -> "Respinto";
            case "CANCELLED" -> "Annullato";
            case "FORWARDED" -> "Inoltrato";
            default -> status;
        };
    }

    static String stateColor(String status) {
        return switch (status == null ? "" : status) {
            case "RUNNING", "COMPLETING" -> "#FFF59D";
            case "ERROR" -> "#FFCDD2";
            case "COMPLETED" -> "#C8E6C9";
            case "REJECTED" -> "#FFCCBC";
            default -> "#E0E0E0";
        };
    }

    private static String day(Instant i) { return i == null ? "" : DAY.format(i.atZone(EwfApp.get().zone())); }

    // ------------------------------------------------------------------ riga

    public class Item extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final ParticipatedProcess p;

        Item(ParticipatedProcess p) { this.p = p; }

        @Override public void onRowExecute() { onOpen(null); }

        public void onOpen(ActionEvent ae) { if (m_listener != null) m_listener.reactOnOpen(p.processId()); }
        public void onAttachments(ActionEvent ae) { openInfo(p, ProcessInfoUI.Mode.ATTACHMENTS); }
        public void onComments(ActionEvent ae) { openInfo(p, ProcessInfoUI.Mode.COMMENTS); }
        public void onHistory(ActionEvent ae) { openInfo(p, ProcessInfoUI.Mode.HISTORY); }
        public void onCancel(ActionEvent ae) { if (isCancellable()) openCancel(p); }

        private InfoCounts counts() { return m_counts.getOrDefault(p.processId(), InfoCounts.NONE); }
        public boolean isAttachmentsEnabled() { return counts().attachments() != 0; }
        public boolean isCommentsEnabled() { return counts().comments() > 0; }
        public boolean isHistoryEnabled() { return counts().history() > 0; }
        public String getAttachmentsText() { int n = counts().attachments(); return n > 0 ? "Allegati (" + n + ")" : "Allegati"; }
        public String getCommentsText() { int n = counts().comments(); return n > 0 ? "Commenti (" + n + ")" : "Commenti"; }
        public String getHistoryText() { int n = counts().history(); return n > 0 ? "Storico (" + n + ")" : "Storico"; }
        public String getAttachmentsBg() { return counts().attachments() > 0 ? HIGHLIGHT : null; }
        public String getCommentsBg() { return counts().comments() > 0 ? HIGHLIGHT : null; }
        public String getHistoryBg() { return counts().history() > 0 ? HIGHLIGHT : null; }

        public boolean isCancellable() {
            return m_mode == Mode.ADMIN && p.endedAt() == null && !"CANCELLED".equals(p.status());
        }

        public String getProcess() { return p.processTypeText(); }
        public String getDocument() { return p.documentKey(); }
        public String getSummary() { return p.summary() == null ? "" : p.summary(); }
        public String getState() { return stateText(p.status()); }
        public String getStateColor() { return stateColor(p.status()); }
        public String getStarted() { return day(p.startedAt()); }
        public long getStartedSort() { return p.startedAt() == null ? 0 : p.startedAt().toEpochMilli(); }
        public String getEnded() { return day(p.endedAt()); }
        public long getEndedSort() { return p.endedAt() == null ? Long.MAX_VALUE : p.endedAt().toEpochMilli(); }
        public String getStarter() { return p.starter() == null ? "" : p.starter(); }
        public String getRole() { return p.myRole() == null ? "" : p.myRole(); }
        public String getWhere() { return p.openTasks() == null ? "" : p.openTasks(); }
    }
}
