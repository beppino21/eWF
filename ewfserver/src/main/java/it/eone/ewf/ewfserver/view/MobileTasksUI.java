package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.app.JobHost;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.InfoCounts;
import it.eone.ewf.service.Views.Light;
import it.eone.ewf.service.Views.Reason;
import it.eone.ewf.service.Views.WorkItem;
import it.eone.ewf.service.Views.WorklistFilter;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Versione da telefono della lista attivita': una "card" per task, tre filtri, Aggiorna.
 * Usa gli stessi servizi della lista desktop (EwfService.worklist e infoCounts): nessuna logica propria.
 */
@CCGenClass(expressionBase = "#{d.MobileTasksUI}")
public class MobileTasksUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    public interface IListener extends Serializable {
        void reactOnOpen(long processId);
        void reactOnLogout();
        /** Passa alla versione completa (desktop) dell'applicazione. */
        void reactOnDesktop();
    }

    public enum Filter { ALL, LATE, OTHERS }

    private IListener m_listener;
    private Filter m_filter = Filter.ALL;
    private List<WorkItem> m_all = List.of();
    private final List<Card> m_cards = new ArrayList<>();
    private Map<Long, InfoCounts> m_counts = Map.of();
    private String m_updatedAt = "";

    public MobileTasksUI() {
        SessionGuard.requireUser("/MobileTasks.xml");
    }

    @Override public String getPageName() { return "/MobileTasks.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.MobileTasksUI}"; }

    public void prepare(IListener listener) {
        m_listener = listener;
        reload();
    }

    public void reload() {
        EwfService svc = EwfApp.get().service();
        long uid = EwfSession.instance().userId();
        try {
            m_all = svc.worklist(uid, WorklistFilter.all()).items();
            List<Long> ids = new ArrayList<>();
            for (WorkItem w : m_all) ids.add(w.processId());
            m_counts = svc.infoCounts(uid, ids);
        } catch (ServiceException e) {
            m_all = List.of();
            m_counts = Map.of();
            Statusbar.outputError(e.getMessage());
        }
        m_updatedAt = "Aggiornato alle " + HM.format(LocalTime.now(EwfApp.get().zone()));
        applyFilter();
    }

    private void applyFilter() {
        m_cards.clear();
        for (WorkItem w : m_all) if (matches(w, m_filter)) m_cards.add(new Card(w));
    }

    private static boolean matches(WorkItem w, Filter f) {
        return switch (f) {
            case ALL -> true;
            case LATE -> w.light() == Light.RED;
            case OTHERS -> w.reason() != Reason.OWN;
        };
    }

    private long count(Filter f) { return m_all.stream().filter(w -> matches(w, f)).count(); }

    // ------------------------------------------------------------------ azioni

    public void onFilterAll(ActionEvent ae) { m_filter = Filter.ALL; applyFilter(); }
    public void onFilterLate(ActionEvent ae) { m_filter = Filter.LATE; applyFilter(); }
    public void onFilterOthers(ActionEvent ae) { m_filter = Filter.OTHERS; applyFilter(); }

    /** Come la lista desktop: prima legge subito la coda degli avvii da SAP, poi rilegge. */
    public void onRefresh(ActionEvent ae) {
        JobHost.QueueRead r = JobHost.readStartQueueNow(Duration.ofSeconds(6));
        reload();
        if (r == JobHost.QueueRead.FAILED)
            Statusbar.outputWarning("Lettura dei nuovi documenti da SAP non riuscita: lista con i dati gia' presenti.");
    }

    public void onLogout(ActionEvent ae) { if (m_listener != null) m_listener.reactOnLogout(); }
    public void onDesktop(ActionEvent ae) { if (m_listener != null) m_listener.reactOnDesktop(); }

    // ------------------------------------------------------------------ binding

    public List<Card> getCards() { return m_cards; }
    public boolean isEmpty() { return m_cards.isEmpty(); }
    public String getEmptyText() {
        return m_all.isEmpty() ? "Nessuna attivita' da fare." : "Nessuna attivita' con questo filtro.";
    }
    public String getUpdatedAt() { return m_updatedAt; }
    public String getCountText() {
        int n = m_all.size();
        return n == 1 ? "1 da fare" : n + " da fare";
    }
    public String getUserName() {
        var u = EwfSession.instance().user();
        return u == null ? "" : u.displayName() + " · " + u.login();
    }

    public String getAllText() { return "Tutte (" + count(Filter.ALL) + ")"; }
    public String getLateText() { return "In ritardo (" + count(Filter.LATE) + ")"; }
    public String getOthersText() { return "Per altri (" + count(Filter.OTHERS) + ")"; }
    public String getAllBg() { return chipBg(Filter.ALL); }
    public String getLateBg() { return chipBg(Filter.LATE); }
    public String getOthersBg() { return chipBg(Filter.OTHERS); }
    public String getAllFg() { return chipFg(Filter.ALL); }
    public String getLateFg() { return chipFg(Filter.LATE); }
    public String getOthersFg() { return chipFg(Filter.OTHERS); }
    public String getAllColor() { return chipColor(Filter.ALL); }
    public String getLateColor() { return chipColor(Filter.LATE); }
    public String getOthersColor() { return chipColor(Filter.OTHERS); }
    private String chipColor(Filter f) { return m_filter == f ? "#FFFFFF" : "#1F2A33"; }
    private String chipBg(Filter f) { return m_filter == f ? "#1565C0" : "#FFFFFF"; }
    private String chipFg(Filter f) { return m_filter == f ? "size:14;weight:bold;color:#FFFFFF" : "size:14;color:#1F2A33"; }

    // ------------------------------------------------------------------ card

    public class Card implements Serializable {
        private static final long serialVersionUID = 1L;
        private final WorkItem w;

        Card(WorkItem w) { this.w = w; }

        public void onOpen(ActionEvent ae) { if (m_listener != null) m_listener.reactOnOpen(w.processId()); }

        public String getProcess() { return w.processTypeText(); }
        public String getDocument() { return w.documentKey(); }
        public String getValue() {
            return w.value() == null ? "" : EwfService.formatAmount(w.value().amount()) + " " + w.value().currency();
        }
        /** Riepilogo del tipo processo (es. fornitore); se manca, chi ha avviato. */
        public String getSummary() {
            if (w.summary() != null && !w.summary().isBlank()) return w.summary();
            return w.starter() == null ? "" : "Richiesto da " + w.starter();
        }
        public String getTask() { return w.taskText() + (w.webActionable() ? "" : " (in SAP GUI)"); }
        public String getForWhom() {
            return w.reason() == Reason.SUBSTITUTE ? "per conto di " + w.forWhom()
                    : w.reason() == Reason.TEAM ? "team " + w.forWhom() : "";
        }
        public String getAttachments() {
            int n = m_counts.getOrDefault(w.processId(), InfoCounts.NONE).attachments();
            return n > 0 ? "Allegati: " + n : "";
        }
        public String getBadge() {
            int d = w.daysWaiting();
            if (w.light() == Light.RED) return " In ritardo · " + d + " gg ";
            return d == 0 ? " Oggi " : d == 1 ? " Da 1 giorno " : " Da " + d + " giorni ";
        }
        public String getBadgeBg() {
            return w.light() == Light.RED ? "#FDE2E2" : w.light() == Light.YELLOW ? "#FFF0C2" : "#DFF2E1";
        }
        public String getBadgeColor() {
            return w.light() == Light.RED ? "#8E1B1B" : w.light() == Light.YELLOW ? "#7A4A00" : "#1B5E20";
        }
        public String getBadgeFont() {
            String c = w.light() == Light.RED ? "#8E1B1B" : w.light() == Light.YELLOW ? "#7A4A00" : "#1B5E20";
            return "size:12;weight:bold;color:" + c;
        }
    }
}
