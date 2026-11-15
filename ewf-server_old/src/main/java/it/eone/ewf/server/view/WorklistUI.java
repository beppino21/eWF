package it.eone.ewf.server.view;

import it.eone.ewf.server.app.EwfApp;
import it.eone.ewf.server.context.EwfSession;
import it.eone.ewf.server.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.Column;
import it.eone.ewf.service.Views.Light;
import it.eone.ewf.service.Views.Reason;
import it.eone.ewf.service.Views.TypeCount;
import it.eone.ewf.service.Views.WorkItem;
import it.eone.ewf.service.Views.Worklist;
import it.eone.ewf.service.Views.WorklistFilter;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.format.DateTimeFormatter;
import java.util.List;

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
    }

    private IListener m_listener;
    private final FIXGRIDListBinding<Item> m_grid = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_typesVVB = new ValidValuesBinding();
    private String m_type;
    private String m_text;
    private String m_status = "";
    private List<Column> m_columns = List.of();

    public WorklistUI() {
        SessionGuard.requireUser("/Worklist.xml");
    }

    @Override public String getPageName() { return "/Worklist.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.WorklistUI}"; }

    public void prepare(IListener listener) {
        m_listener = listener;
        reload();
    }

    /** Rilegge dal database mantenendo filtri e ricerca. */
    public void reload() {
        EwfService svc = EwfApp.get().service();
        try {
            Worklist w = svc.worklist(EwfSession.instance().userId(), new WorklistFilter(m_type, m_text));
            m_typesVVB.clear();
            for (TypeCount t : w.typeCounts())
                m_typesVVB.addValidValue(t.processType(), (t.text() == null ? t.processType() : t.text()) + " (" + t.count() + ")");
            if (m_type != null && w.typeCounts().stream().noneMatch(t -> t.processType().equals(m_type))) {
                m_type = null;                                       // il tipo filtrato non ha piu' attivita'
                w = svc.worklist(EwfSession.instance().userId(), new WorklistFilter(null, m_text));
            }
            m_columns = m_type == null ? List.of() : svc.columns(m_type);
            m_grid.getItems().clear();
            for (WorkItem i : w.items()) m_grid.getItems().add(new Item(i));
            int total = w.typeCounts().stream().mapToInt(TypeCount::count).sum();
            m_status = w.items().size() == total ? total + " attivita'" : w.items().size() + " di " + total + " attivita'";
        } catch (ServiceException e) {
            m_grid.getItems().clear();
            m_status = "";
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ azioni

    public void onRefresh(ActionEvent ae) { reload(); }
    public void onFilter(ActionEvent ae) { reload(); }

    public void onResetFilters(ActionEvent ae) {
        m_type = null;
        m_text = null;
        reload();
    }

    public void onLogout(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnLogout();
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<Item> getGrid() { return m_grid; }
    public ValidValuesBinding getTypesVVB() { return m_typesVVB; }
    public String getType() { return m_type; }
    public void setType(String v) { m_type = v == null || v.isBlank() ? null : v; }
    public String getText() { return m_text; }
    public void setText(String v) { m_text = v; }
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

    public class Item extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final WorkItem w;

        Item(WorkItem w) { this.w = w; }

        @Override public void onRowExecute() {
            if (m_listener != null) m_listener.reactOnOpen(w.processId());
        }

        public void onOpen(ActionEvent ae) { onRowExecute(); }

        public String getDays() { return String.valueOf(w.daysWaiting()); }
        public int getDaysSort() { return w.daysWaiting(); }
        public String getLightColor() {
            return w.light() == Light.RED ? "#E57373" : w.light() == Light.YELLOW ? "#FFD54F" : "#81C784";
        }
        public String getProcessType() { return w.processTypeText(); }
        public String getDocument() { return w.documentKey(); }
        public String getSummary() { return w.summary() == null ? "" : w.summary(); }
        public String getTaskText() { return w.taskText() + (w.webActionable() ? "" : " (in SAP GUI)"); }
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

        private String col(int i) {
            if (i >= m_columns.size()) return "";
            String v = w.attributes().get(m_columns.get(i).key());
            return v == null ? "" : v;
        }
    }
}
