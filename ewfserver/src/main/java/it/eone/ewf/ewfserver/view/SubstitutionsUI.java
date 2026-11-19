package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.SubstitutionService;
import it.eone.ewf.service.SubstitutionService.Candidate;
import it.eone.ewf.service.SubstitutionService.Substitution;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Sostituzioni decise dall'utente stesso ("chi mi sostituisce quando sono via") e, in sola lettura,
 * quelle in cui e' lui a sostituire qualcuno. Chi non ha il permesso (utente o classe utente con
 * "puo' indicare sostituti") vede le proprie ma non le cambia: le gestisce l'amministratore.
 */
@CCGenClass(expressionBase = "#{d.SubstitutionsUI}")
public class SubstitutionsUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FIXGRIDListBinding<MineItem> m_mine = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<OtherItem> m_others = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_candidatesVVB = new ValidValuesBinding();
    private boolean m_canManage;
    private String m_substitute;
    private LocalDate m_from;
    private LocalDate m_to;
    private IBackToMenu m_back;

    public SubstitutionsUI() {
        SessionGuard.requireUser("/Substitutions.xml");
        reload();
    }

    @Override public String getPageName() { return "/Substitutions.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.SubstitutionsUI}"; }

    private static SubstitutionService svc() { return EwfApp.get().substitutions(); }
    private static long me() { return EwfSession.instance().userId(); }

    public void reload() {
        try {
            m_canManage = svc().canManage(me());
            m_mine.getItems().clear();
            for (Substitution s : svc().mine(me())) m_mine.getItems().add(new MineItem(s));
            m_others.getItems().clear();
            for (Substitution s : svc().asSubstitute(me())) m_others.getItems().add(new OtherItem(s));
            m_candidatesVVB.clear();
            if (m_canManage)
                for (Candidate c : svc().candidates(me())) m_candidatesVVB.addValidValue(String.valueOf(c.id()), c.name() + " (" + c.login() + ")");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ azioni

    public void setBack(IBackToMenu back) { m_back = back; }

    public void onBackToMenu(ActionEvent ae) {
        if (m_back != null) m_back.backToMenu();
    }

    public void onRefresh(ActionEvent ae) { reload(); }

    public void onAdd(ActionEvent ae) {
        if (m_substitute == null || m_from == null || m_to == null) {
            Statusbar.outputError("Indicare il sostituto e il periodo (dal / al)");
            return;
        }
        try {
            Substitution s = svc().add(me(), Long.parseLong(m_substitute), m_from, m_to);
            Statusbar.outputSuccess("Sostituzione registrata: " + s.substituteName() + " dal " + DAY.format(s.from())
                    + " al " + DAY.format(s.to()));
            m_substitute = null;
            m_from = null;
            m_to = null;
            reload();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private void remove(Substitution s) {
        String text = s.activeToday()
                ? "La sostituzione di " + s.substituteName() + " e' in corso: terminarla oggi?"
                : "Eliminare la sostituzione di " + s.substituteName() + " dal " + DAY.format(s.from()) + "?";
        YESNOPopup.createInstance("Sostituzioni", text, new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    svc().remove(me(), s.id());
                    Statusbar.outputSuccess("Sostituzione tolta");
                } catch (ServiceException e) {
                    Statusbar.outputError(e.getMessage());
                }
                reload();
            }
            @Override public void reactOnNo() { }
        });
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<MineItem> getMine() { return m_mine; }
    public FIXGRIDListBinding<OtherItem> getOthers() { return m_others; }
    public ValidValuesBinding getCandidatesVVB() { return m_candidatesVVB; }
    public boolean isCanManage() { return m_canManage; }
    public boolean isReadOnlyNoticeVisible() { return !m_canManage; }
    public String getMineTitle() {
        return m_mine.getItems().isEmpty() ? "Chi mi sostituisce — nessuno" : "Chi mi sostituisce (" + m_mine.getItems().size() + ")";
    }
    public String getOthersTitle() {
        return m_others.getItems().isEmpty() ? "Chi sto sostituendo — nessuno" : "Chi sto sostituendo (" + m_others.getItems().size() + ")";
    }
    public String getSubstitute() { return m_substitute; }
    public void setSubstitute(String v) { m_substitute = v == null || v.isBlank() ? null : v; }
    public LocalDate getFrom() { return m_from; }
    public void setFrom(LocalDate v) { m_from = v; }
    public LocalDate getTo() { return m_to; }
    public void setTo(LocalDate v) { m_to = v; }

    private static String state(Substitution s) { return s.activeToday() ? "In corso" : "Programmata"; }
    private static String stateColor(Substitution s) { return s.activeToday() ? "#FFF59D" : null; }

    // ------------------------------------------------------------------ righe

    public class MineItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Substitution s;
        MineItem(Substitution s) { this.s = s; }
        public String getName() { return s.substituteName(); }
        public String getFrom() { return DAY.format(s.from()); }
        public String getTo() { return DAY.format(s.to()); }
        public long getFromSort() { return s.from().toEpochDay(); }
        public String getState() { return state(s); }
        public String getStateColor() { return stateColor(s); }
        public String getRemoveText() { return s.activeToday() ? "Termina" : "Elimina"; }
        public void onRemove(ActionEvent ae) { remove(s); }
    }

    public class OtherItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Substitution s;
        OtherItem(Substitution s) { this.s = s; }
        public String getName() { return s.userName(); }
        public String getFrom() { return DAY.format(s.from()); }
        public String getTo() { return DAY.format(s.to()); }
        public long getFromSort() { return s.from().toEpochDay(); }
        public String getState() { return state(s); }
        public String getStateColor() { return stateColor(s); }
    }
}
