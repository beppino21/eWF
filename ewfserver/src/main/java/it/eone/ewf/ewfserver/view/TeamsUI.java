package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.AdminService.Membership;
import it.eone.ewf.service.AdminService.TeamRow;
import it.eone.ewf.service.ServiceException;
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
 * Team e appartenenze (amministratori). Un utente ha una sola appartenenza per team, con validita' dal/al;
 * togliere qualcuno chiude l'appartenenza a ieri (resta nello storico). Deve restare almeno un amministratore.
 */
@CCGenClass(expressionBase = "#{d.TeamsUI}")
public class TeamsUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private IBackToMenu m_back;
    private final FIXGRIDListBinding<TeamItem> m_teams = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<MemberItem> m_members = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_usersVVB = new ValidValuesBinding();

    private String m_selected;          // codice del team selezionato
    private boolean m_newTeam;
    private String m_code;
    private String m_description;
    private String m_user;
    private LocalDate m_from;
    private LocalDate m_to;

    public TeamsUI() {
        SessionGuard.requireUser("/Teams.xml");
    }

    @Override public String getPageName() { return "/Teams.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.TeamsUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static AdminService admin() { return EwfApp.get().admin(); }

    public void prepare(IBackToMenu back) {
        m_back = back;
        try {
            m_usersVVB.clear();
            for (AdminService.UserRef u : admin().activeUsers(uid())) m_usersVVB.addValidValue(String.valueOf(u.id()), u.name() + " (" + u.login() + ")");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reloadTeams();
    }

    private void reloadTeams() {
        m_teams.getItems().clear();
        try {
            for (TeamRow t : admin().teams(uid())) {
                TeamItem it = new TeamItem(t);
                m_teams.getItems().add(it);
                if (t.code().equals(m_selected)) m_teams.selectItem(it);
            }
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reloadMembers();
    }

    private void reloadMembers() {
        m_members.getItems().clear();
        if (m_selected == null) return;
        try {
            for (Membership m : admin().members(uid(), m_selected)) m_members.getItems().add(new MemberItem(m));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private void select(TeamRow t) {
        m_selected = t.code();
        m_newTeam = false;
        m_code = t.code();
        m_description = t.description();
        m_user = null;
        m_from = null;
        m_to = null;
        reloadMembers();
    }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) { if (m_back != null) m_back.backToMenu(); }

    public void onNewTeam(ActionEvent ae) {
        m_selected = null;
        m_newTeam = true;
        m_code = null;
        m_description = null;
        m_teams.deselectCurrentSelection();
        m_members.getItems().clear();
    }

    public void onSaveTeam(ActionEvent ae) {
        try {
            admin().saveTeam(uid(), m_code, m_description, m_newTeam);
            if (m_newTeam) m_selected = m_code.trim().toUpperCase();
            m_newTeam = false;
            Statusbar.outputSuccess("Team salvato");
            reloadTeams();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onAddMember(ActionEvent ae) {
        if (m_selected == null || m_user == null) {
            Statusbar.outputError("Scegliere l'utente da aggiungere");
            return;
        }
        try {
            admin().setMember(uid(), m_selected, Long.parseLong(m_user), m_from, m_to);
            Statusbar.outputSuccess("Appartenenza salvata");
            m_user = null;
            m_from = null;
            m_to = null;
            reloadTeams();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<TeamItem> getTeams() { return m_teams; }
    public FIXGRIDListBinding<MemberItem> getMembers() { return m_members; }
    public ValidValuesBinding getUsersVVB() { return m_usersVVB; }
    public boolean isEditing() { return m_newTeam || m_selected != null; }
    public boolean isNotEditing() { return !isEditing(); }
    public boolean isExistingTeam() { return !m_newTeam && m_selected != null; }
    public boolean isCodeEditable() { return m_newTeam; }
    public String getEditTitle() { return m_newTeam ? "Nuovo team" : "Team " + m_selected; }
    public String getCode() { return m_code; }
    public void setCode(String v) { if (m_newTeam) m_code = v; }
    public String getDescription() { return m_description; }
    public void setDescription(String v) { m_description = v; }
    public String getUser() { return m_user; }
    public void setUser(String v) { m_user = v == null || v.isBlank() ? null : v; }
    public LocalDate getFrom() { return m_from; }
    public void setFrom(LocalDate v) { m_from = v; }
    public LocalDate getTo() { return m_to; }
    public void setTo(LocalDate v) { m_to = v; }

    static String day(LocalDate d, String ifNull) { return d == null ? ifNull : DAY.format(d); }

    // ------------------------------------------------------------------ righe

    public class TeamItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TeamRow t;
        TeamItem(TeamRow t) { this.t = t; }
        @Override public void onRowSelect() { select(t); }
        public String getCode() { return t.code(); }
        public String getDescription() { return t.description(); }
        public String getMembers() { return String.valueOf(t.activeMembers()); }
        public String getAdminMark() { return t.code().equals(admin().adminTeam()) ? "amministratori" : ""; }
    }

    public class MemberItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Membership m;
        MemberItem(Membership m) { this.m = m; }
        public String getName() { return m.userName(); }
        public String getLogin() { return m.login(); }
        public String getFrom() { return day(m.from(), "sempre"); }
        public String getTo() { return day(m.to(), "senza scadenza"); }
        public String getState() {
            if (m.activeToday()) return "Attivo";
            return m.from() != null && m.from().isAfter(LocalDate.now(EwfApp.get().zone())) ? "Futuro" : "Concluso";
        }
        public String getStateColor() { return m.activeToday() ? "#C8E6C9" : "Futuro".equals(getState()) ? "#FFF59D" : "#E0E0E0"; }
        public boolean isRemovable() { return !"Concluso".equals(getState()); }

        public void onEdit(ActionEvent ae) {
            m_user = String.valueOf(m.userId());
            m_from = m.from();
            m_to = m.to();
        }

        public void onRemove(ActionEvent ae) {
            YESNOPopup.createInstance("Team " + m.teamCode(), "Togliere " + m.userName() + " dal team da oggi?"
                    + " Le attivita' gia' arrivate al team restano al team.", new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    try {
                        admin().endMember(uid(), m.teamCode(), m.userId());
                        Statusbar.outputSuccess(m.userName() + " tolto dal team");
                    } catch (ServiceException e) {
                        Statusbar.outputError(e.getMessage());
                    }
                    reloadTeams();
                }
                @Override public void reactOnNo() { }
            });
        }
    }
}
