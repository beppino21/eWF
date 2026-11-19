package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.ServiceException;
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

/** Sostituzione generalizzata (amministratori): sostituzioni di tutti gli utenti, in corso e future. */
@CCGenClass(expressionBase = "#{d.AdminSubstitutionsUI}")
public class AdminSubstitutionsUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private IBackToMenu m_back;
    private final FIXGRIDListBinding<Item> m_grid = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_usersVVB = new ValidValuesBinding();
    private String m_user;
    private String m_substitute;
    private LocalDate m_from;
    private LocalDate m_to;

    public AdminSubstitutionsUI() {
        SessionGuard.requireUser("/AdminSubstitutions.xml");
    }

    @Override public String getPageName() { return "/AdminSubstitutions.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.AdminSubstitutionsUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static AdminService admin() { return EwfApp.get().admin(); }

    public void prepare(IBackToMenu back) {
        m_back = back;
        try {
            m_usersVVB.clear();
            for (AdminService.UserRef u : admin().activeUsers(uid())) m_usersVVB.addValidValue(String.valueOf(u.id()), u.name());
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reload();
    }

    private void reload() {
        m_grid.getItems().clear();
        try {
            for (Substitution s : admin().allSubstitutions(uid())) m_grid.getItems().add(new Item(s));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onBackToMenu(ActionEvent ae) { if (m_back != null) m_back.backToMenu(); }
    public void onRefresh(ActionEvent ae) { reload(); }

    public void onAdd(ActionEvent ae) {
        if (m_user == null || m_substitute == null || m_from == null || m_to == null) {
            Statusbar.outputError("Indicare titolare, sostituto e periodo");
            return;
        }
        try {
            Substitution s = admin().addSubstitution(uid(), Long.parseLong(m_user), Long.parseLong(m_substitute), m_from, m_to);
            Statusbar.outputSuccess("Registrata: " + s.substituteName() + " sostituisce " + s.userName()
                    + " dal " + DAY.format(s.from()) + " al " + DAY.format(s.to()));
            m_substitute = null;
            m_from = null;
            m_to = null;
            reload();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public FIXGRIDListBinding<Item> getGrid() { return m_grid; }
    public ValidValuesBinding getUsersVVB() { return m_usersVVB; }
    public String getUser() { return m_user; }
    public void setUser(String v) { m_user = v == null || v.isBlank() ? null : v; }
    public String getSubstitute() { return m_substitute; }
    public void setSubstitute(String v) { m_substitute = v == null || v.isBlank() ? null : v; }
    public LocalDate getFrom() { return m_from; }
    public void setFrom(LocalDate v) { m_from = v; }
    public LocalDate getTo() { return m_to; }
    public void setTo(LocalDate v) { m_to = v; }

    public class Item extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Substitution s;
        Item(Substitution s) { this.s = s; }
        public String getUserName() { return s.userName(); }
        public String getSubstituteName() { return s.substituteName(); }
        public String getFrom() { return DAY.format(s.from()); }
        public long getFromSort() { return s.from().toEpochDay(); }
        public String getTo() { return DAY.format(s.to()); }
        public String getState() { return s.activeToday() ? "In corso" : "Programmata"; }
        public String getStateColor() { return s.activeToday() ? "#FFF59D" : null; }
        public String getRemoveText() { return s.activeToday() ? "Termina" : "Elimina"; }

        public void onRemove(ActionEvent ae) {
            String q = s.activeToday()
                    ? "Terminare oggi la sostituzione di " + s.userName() + " (" + s.substituteName() + ")?"
                    : "Eliminare la sostituzione di " + s.userName() + " (" + s.substituteName() + ")?";
            YESNOPopup.createInstance("Sostituzioni", q, new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    try {
                        admin().removeSubstitution(uid(), s.id());
                        Statusbar.outputSuccess("Sostituzione tolta");
                    } catch (ServiceException e) {
                        Statusbar.outputError(e.getMessage());
                    }
                    reload();
                }
                @Override public void reactOnNo() { }
            });
        }
    }
}
