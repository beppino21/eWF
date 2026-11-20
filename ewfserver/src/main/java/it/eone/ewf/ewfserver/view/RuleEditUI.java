package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.AssignmentService.RuleEdit;
import it.eone.ewf.service.AssignmentService.RuleRow;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Nuova regola di assegnazione o modifica di una esistente. Campi vuoti = qualsiasi valore. */
@CCGenClass(expressionBase = "#{d.RuleEditUI}")
public class RuleEditUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private TaskEditUI.IListener m_listener;
    private String m_role;
    private Long m_id;
    private ValidValuesBinding m_typesVVB;
    private final ValidValuesBinding m_usersVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_teamsVVB = new ValidValuesBinding();

    private String m_type;
    private String m_group;
    private String m_plant;
    private String m_sloc;
    private String m_from;
    private String m_to;
    private String m_currency;
    private String m_user;
    private String m_team;
    private LocalDate m_validFrom;
    private LocalDate m_validTo;

    public RuleEditUI() {
        SessionGuard.requireUser("/RuleEdit.xml");
    }

    @Override public String getPageName() { return "/RuleEdit.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.RuleEditUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }

    public void prepare(String role, RuleRow r, ValidValuesBinding types, TaskEditUI.IListener listener) {
        m_role = role;
        m_typesVVB = types;
        m_listener = listener;
        try {
            for (AdminService.UserRef u : EwfApp.get().admin().activeUsers(uid()))
                m_usersVVB.addValidValue(String.valueOf(u.id()), u.name() + " (" + u.login() + ")");
            for (Choice c : EwfApp.get().designer().teams(uid()))
                m_teamsVVB.addValidValue(c.code(), c.code() + " – " + c.text());
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        if (r == null) return;
        m_id = r.id();
        m_type = r.processType();
        m_group = r.purchasingGroup();
        m_plant = r.plant();
        m_sloc = r.storageLocation();
        m_from = r.amountFrom() == null ? null : r.amountFrom().stripTrailingZeros().toPlainString();
        m_to = r.amountTo() == null ? null : r.amountTo().stripTrailingZeros().toPlainString();
        m_currency = r.currency();
        m_user = r.userId() == null ? null : String.valueOf(r.userId());
        if (r.userId() != null && !r.userActive())      // destinatario disattivato: resta visibile nella scelta
            m_usersVVB.addValidValue(m_user, r.userName() + " (disattivato)");
        m_team = r.teamCode();
        m_validFrom = r.validFrom();
        m_validTo = r.validTo();
    }

    public void onSave(ActionEvent ae) {
        BigDecimal from, to;
        try {
            from = decimal(m_from);
            to = decimal(m_to);
        } catch (NumberFormatException e) {
            Statusbar.outputError("Importo non valido (es. 10000 oppure 10.000,50)");
            return;
        }
        try {
            EwfApp.get().assignments().saveRule(uid(), new RuleEdit(m_id, m_role, m_type, m_group, m_plant, m_sloc, from, to,
                    m_currency, m_user == null ? null : Long.valueOf(m_user), m_team, m_validFrom, m_validTo));
            if (m_listener != null) m_listener.saved();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onCancel(ActionEvent ae) { if (m_listener != null) m_listener.cancelled(); }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return null;
        return new BigDecimal(s.trim().replace(".", "").replace(',', '.'));
    }

    private static String nz(String v) { return v == null || v.isBlank() ? null : v; }

    public ValidValuesBinding getTypesVVB() { return m_typesVVB; }
    public ValidValuesBinding getUsersVVB() { return m_usersVVB; }
    public ValidValuesBinding getTeamsVVB() { return m_teamsVVB; }
    public String getType() { return m_type; }
    public void setType(String v) { m_type = nz(v); }
    public String getGroup() { return m_group; }
    public void setGroup(String v) { m_group = nz(v); }
    public String getPlant() { return m_plant; }
    public void setPlant(String v) { m_plant = nz(v); }
    public String getSloc() { return m_sloc; }
    public void setSloc(String v) { m_sloc = nz(v); }
    public String getFrom() { return m_from; }
    public void setFrom(String v) { m_from = v; }
    public String getTo() { return m_to; }
    public void setTo(String v) { m_to = v; }
    public String getCurrency() { return m_currency; }
    public void setCurrency(String v) { m_currency = nz(v); }
    public String getUser() { return m_user; }
    public void setUser(String v) { m_user = nz(v); }
    public String getTeam() { return m_team; }
    public void setTeam(String v) { m_team = nz(v); }
    public LocalDate getValidFrom() { return m_validFrom; }
    public void setValidFrom(LocalDate v) { m_validFrom = v; }
    public LocalDate getValidTo() { return m_validTo; }
    public void setValidTo(LocalDate v) { m_validTo = v; }
}
