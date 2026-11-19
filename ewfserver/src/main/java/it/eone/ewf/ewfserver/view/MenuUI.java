package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.Light;
import it.eone.ewf.service.Views.Reason;
import it.eone.ewf.service.Views.WorkItem;
import it.eone.ewf.service.Views.WorklistFilter;
import it.eone.ewf.service.auth.AuthService;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pagina iniziale dopo il logon, come nel ticketing: a sinistra le funzioni abilitate (card),
 * a destra la sintesi delle attivita' in carico per semaforo.
 */
@CCGenClass(expressionBase = "#{d.MenuUI}")
public class MenuUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TASKS = "TASKS", SUBSTITUTIONS = "SUBSTITUTIONS", PASSWORD = "PASSWORD", ERRORS = "ERRORS";

    public interface IListener extends Serializable {
        void reactOnMenuChoice(String choice);
        void reactOnLogout();
    }

    private IListener m_listener;
    private final FIXGRIDListBinding<Card> m_cards = new FIXGRIDListBinding<>();
    private final List<SummaryLine> m_summary = new ArrayList<>();
    private int m_total;
    private int m_fromSubstitutions;

    public MenuUI() {
        SessionGuard.requireUser("/Menu.xml");
    }

    @Override public String getPageName() { return "/Menu.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.MenuUI}"; }

    /** Costruisce card e sintesi; va richiamato ogni volta che si torna al menu (i numeri cambiano). */
    public void prepare(boolean admin, int errorCount, IListener listener) {
        m_listener = listener;
        m_cards.getItems().clear();
        m_cards.getItems().add(new Card(TASKS, "Lista attività'", "Le attivita' da approvare o eseguire, tue, dei tuoi team e di chi sostituisci", 0));
        m_cards.getItems().add(new Card(SUBSTITUTIONS, "Auto-sostituzione", "Chi ti sostituisce quando sei assente, e chi stai sostituendo", 0));
        if (admin)
            m_cards.getItems().add(new Card(ERRORS, "Processi in errore", "Chiamate a SAP con esito incerto e azioni finali non riuscite", errorCount));
        m_cards.getItems().add(new Card(PASSWORD, "Cambio password", "Cambia la tua password di accesso a eWF", 0));
        loadSummary();
    }

    private void loadSummary() {
        m_summary.clear();
        m_total = 0;
        m_fromSubstitutions = 0;
        int green = 0, yellow = 0, red = 0;
        try {
            for (WorkItem w : EwfApp.get().service().worklist(EwfSession.instance().userId(), WorklistFilter.all()).items()) {
                m_total++;
                if (w.light() == Light.RED) red++;
                else if (w.light() == Light.YELLOW) yellow++;
                else green++;
                if (w.reason() == Reason.SUBSTITUTE) m_fromSubstitutions++;
            }
        } catch (ServiceException e) {
            System.err.println("[MenuUI] Sintesi non disponibile: " + e.getMessage());
        }
        m_summary.add(new SummaryLine("VERDE", "#81C784", green));
        m_summary.add(new SummaryLine("GIALLO", "#FFD54F", yellow));
        m_summary.add(new SummaryLine("ROSSO", "#E57373", red));
    }

    public void onLogout(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnLogout();
    }

    public void onOpenTasks(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnMenuChoice(TASKS);
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<Card> getCards() { return m_cards; }
    public List<SummaryLine> getSummary() { return m_summary; }
    public String getTotal() { return String.valueOf(m_total); }
    public boolean isHasSubstitutions() { return m_fromSubstitutions > 0; }
    public String getFromSubstitutions() { return String.valueOf(m_fromSubstitutions); }
    public String getWelcome() {
        AuthService.Session u = EwfSession.instance().user();
        return "Benvenuto, " + (u == null ? "" : u.displayName());
    }

    // ------------------------------------------------------------------ voci

    public class Card extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id, title, description;
        private final int badge;

        Card(String id, String title, String description, int badge) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.badge = badge;
        }

        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public boolean isHasBadge() { return badge > 0; }
        public String getBadgeLabel() { return badge + (badge == 1 ? " processo da sistemare" : " processi da sistemare"); }

        public void onSelect(ActionEvent ae) {
            if (m_listener != null) m_listener.reactOnMenuChoice(id);
        }
    }

    public static class SummaryLine implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String label, color;
        private final int count;

        SummaryLine(String label, String color, int count) {
            this.label = label;
            this.color = color;
            this.count = count;
        }

        public String getLabel() { return label; }
        public String getColor() { return color; }
        public String getCount() { return String.valueOf(count); }
    }
}
