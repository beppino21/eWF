package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.AdminService.UserEdit;
import it.eone.ewf.service.AdminService.UserRow;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.OKPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.util.Locale;

/**
 * Gestione utenti (amministratori): elenco, nuovo utente, modifica dei dati essenziali, attivo/disattivo,
 * password provvisoria, sblocco. Le appartenenze ai team per ora restano una gestione da database.
 */
@CCGenClass(expressionBase = "#{d.UsersUI}")
public class UsersUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private IBackToMenu m_back;
    private final FIXGRIDListBinding<Item> m_grid = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_classesVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_substVVB = new ValidValuesBinding();
    private String m_filter;

    // maschera
    private Long m_id;
    private boolean m_editing;
    private String m_login;
    private String m_name;
    private String m_email;
    private String m_sapUser;
    private String m_userClass;
    private String m_canSubstitute = "";      // "" = come la classe, "S" = si', "N" = no
    private boolean m_active = true;

    // team dell'utente in modifica
    private final FIXGRIDListBinding<MemberItem> m_memberships = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_teamsVVB = new ValidValuesBinding();
    private String m_team;
    private java.time.LocalDate m_teamFrom;
    private java.time.LocalDate m_teamTo;

    public UsersUI() {
        SessionGuard.requireUser("/Users.xml");
    }

    @Override public String getPageName() { return "/Users.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.UsersUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static AdminService admin() { return EwfApp.get().admin(); }

    public void prepare(IBackToMenu back) {
        m_back = back;
        m_substVVB.clear();
        m_substVVB.addValidValue("", "come la classe utente");
        m_substVVB.addValidValue("S", "si'");
        m_substVVB.addValidValue("N", "no");
        try {
            m_classesVVB.clear();
            for (AdminService.UserClass c : admin().userClasses(uid())) m_classesVVB.addValidValue(c.code(), c.code() + " - " + c.description());
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        try {
            m_teamsVVB.clear();
            for (AdminService.TeamRow t : admin().teams(uid())) m_teamsVVB.addValidValue(t.code(), t.code() + " - " + t.description());
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reload();
    }

    private void reloadMemberships() {
        m_memberships.getItems().clear();
        if (m_id == null) return;
        try {
            for (AdminService.Membership m : admin().teamsOfUser(uid(), m_id)) m_memberships.getItems().add(new MemberItem(m));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onAddTeam(ActionEvent ae) {
        if (m_id == null || m_team == null) {
            Statusbar.outputError("Scegliere il team");
            return;
        }
        try {
            admin().setMember(uid(), m_team, m_id, m_teamFrom, m_teamTo);
            m_team = null;
            m_teamFrom = null;
            m_teamTo = null;
            reload();
            reloadMemberships();
            Statusbar.outputSuccess("Team aggiornato");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private void reload() {
        m_grid.getItems().clear();
        try {
            String f = m_filter == null ? "" : m_filter.trim().toLowerCase(Locale.ITALY);
            for (UserRow u : admin().users(uid())) {
                String all = (u.login() + " " + u.displayName() + " " + nz(u.email()) + " " + nz(u.sapUser()) + " " + nz(u.teams()))
                        .toLowerCase(Locale.ITALY);
                if (f.isEmpty() || all.contains(f)) m_grid.getItems().add(new Item(u));
            }
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) { if (m_back != null) m_back.backToMenu(); }
    public void onFilter(ActionEvent ae) { reload(); }

    public void onNew(ActionEvent ae) {
        m_id = null;
        m_editing = true;
        m_login = m_name = m_email = m_sapUser = m_userClass = null;
        m_canSubstitute = "";
        m_active = true;
        m_memberships.getItems().clear();
    }

    private void edit(UserRow u) {
        m_id = u.id();
        m_editing = true;
        m_login = u.login();
        m_name = u.displayName();
        m_email = u.email();
        m_sapUser = u.sapUser();
        m_userClass = u.userClass();
        m_canSubstitute = u.canSubstitute() == null ? "" : u.canSubstitute() ? "S" : "N";
        m_active = u.active();
        reloadMemberships();
    }

    public void onCancelEdit(ActionEvent ae) { m_editing = false; m_id = null; }

    public void onSave(ActionEvent ae) {
        Boolean cs = m_canSubstitute == null || m_canSubstitute.isEmpty() ? null : "S".equals(m_canSubstitute);
        boolean isNew = m_id == null;
        try {
            long id = admin().saveUser(uid(), new UserEdit(m_id, m_login, m_name, m_email, m_sapUser, m_userClass, cs, m_active));
            reload();
            m_id = id;
            Statusbar.outputSuccess(isNew ? "Utente creato: ora genera la password provvisoria" : "Utente salvato");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onResetPassword(ActionEvent ae) {
        if (m_id == null) return;
        long id = m_id;
        String who = m_name;
        YESNOPopup.createInstance("Password provvisoria", "Generare una nuova password provvisoria per " + who
                + "? Quella attuale smette di funzionare.", new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    String pwd = admin().resetPassword(uid(), id);
                    OKPopup.createInstance("Password provvisoria",
                            "Password provvisoria di " + who + ":\n\n" + pwd
                            + "\n\nComunicala all'utente: dovra' cambiarla al primo accesso. Non verra' mostrata di nuovo.");
                    reload();
                } catch (ServiceException e) {
                    Statusbar.outputError(e.getMessage());
                }
            }
            @Override public void reactOnNo() { }
        });
    }

    public void onUnlock(ActionEvent ae) {
        if (m_id == null) return;
        try {
            admin().unlock(uid(), m_id);
            reload();
            Statusbar.outputSuccess("Utente sbloccato");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<Item> getGrid() { return m_grid; }
    public ValidValuesBinding getClassesVVB() { return m_classesVVB; }
    public ValidValuesBinding getSubstVVB() { return m_substVVB; }
    public String getFilter() { return m_filter; }
    public void setFilter(String v) { m_filter = v; }
    public boolean isEditing() { return m_editing; }
    public boolean isExisting() { return m_editing && m_id != null; }
    public boolean isLoginEditable() { return m_id == null; }
    public String getEditTitle() { return m_id == null ? "Nuovo utente" : "Utente " + m_login; }
    public String getLogin() { return m_login; }
    public void setLogin(String v) { if (m_id == null) m_login = v; }
    public String getName() { return m_name; }
    public void setName(String v) { m_name = v; }
    public String getEmail() { return m_email; }
    public void setEmail(String v) { m_email = v; }
    public String getSapUser() { return m_sapUser; }
    public void setSapUser(String v) { m_sapUser = v; }
    public String getUserClass() { return m_userClass; }
    public void setUserClass(String v) { m_userClass = v == null || v.isBlank() ? null : v; }
    public String getCanSubstitute() { return m_canSubstitute; }
    public void setCanSubstitute(String v) { m_canSubstitute = v == null ? "" : v; }
    public boolean isActive() { return m_active; }
    public FIXGRIDListBinding<MemberItem> getMemberships() { return m_memberships; }
    public ValidValuesBinding getTeamsVVB() { return m_teamsVVB; }
    public String getTeam() { return m_team; }
    public void setTeam(String v) { m_team = v == null || v.isBlank() ? null : v; }
    public java.time.LocalDate getTeamFrom() { return m_teamFrom; }
    public void setTeamFrom(java.time.LocalDate v) { m_teamFrom = v; }
    public java.time.LocalDate getTeamTo() { return m_teamTo; }
    public void setTeamTo(java.time.LocalDate v) { m_teamTo = v; }
    public void setActive(boolean v) { m_active = v; }

    public class Item extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final UserRow u;
        Item(UserRow u) { this.u = u; }
        @Override public void onRowSelect() { edit(u); }
        public String getLogin() { return u.login(); }
        public String getName() { return u.displayName(); }
        public String getEmail() { return nz(u.email()); }
        public String getSapUser() { return nz(u.sapUser()); }
        public String getUserClass() { return nz(u.userClass()); }
        public String getSubst() { return u.canSubstitute() == null ? "(classe)" : u.canSubstitute() ? "si'" : "no"; }
        public String getTeams() { return nz(u.teams()); }
        public String getState() { return !u.active() ? "Disattivo" : u.locked() ? "Bloccato" : "Attivo"; }
        public String getStateColor() { return !u.active() ? "#E0E0E0" : u.locked() ? "#FFCDD2" : null; }
    }

    public class MemberItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final AdminService.Membership m;
        MemberItem(AdminService.Membership m) { this.m = m; }
        public String getTeam() { return m.teamCode() + " - " + m.teamDescription(); }
        public String getFrom() { return TeamsUI.day(m.from(), "sempre"); }
        public String getTo() { return TeamsUI.day(m.to(), "senza scadenza"); }
        public String getState() { return m.activeToday() ? "Attivo" : m.to() != null && m.to().isBefore(java.time.LocalDate.now(EwfApp.get().zone())) ? "Concluso" : "Futuro"; }
        public boolean isRemovable() { return !"Concluso".equals(getState()); }

        public void onRemove(ActionEvent ae) {
            try {
                admin().endMember(uid(), m.teamCode(), m.userId());
                reload();
                reloadMemberships();
                Statusbar.outputSuccess("Tolto dal team " + m.teamCode());
            } catch (ServiceException e) {
                Statusbar.outputError(e.getMessage());
            }
        }
    }
}
