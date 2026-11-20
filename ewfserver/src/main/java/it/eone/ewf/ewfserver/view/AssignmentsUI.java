package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AssignmentService;
import it.eone.ewf.service.AssignmentService.RoleRow;
import it.eone.ewf.service.AssignmentService.RoleUse;
import it.eone.ewf.service.AssignmentService.RuleRow;
import it.eone.ewf.service.AssignmentService.SimLine;
import it.eone.ewf.service.AssignmentService.SimRequest;
import it.eone.ewf.service.AssignmentService.SimResult;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Assegnazioni (amministratori): ruoli, regole di assegnazione con avvisi, simulatore "chi lo riceve?".
 * Le regole di avvio dei processi arriveranno in questa stessa pagina.
 */
@CCGenClass(expressionBase = "#{d.AssignmentsUI}")
public class AssignmentsUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private IBackToMenu m_back;
    private boolean m_startMode;
    private StartRulesUI m_startRules;
    private final FIXGRIDListBinding<RoleItem> m_roles = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<RuleItem> m_rules = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<SimItem> m_sim = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_typesVVB = new ValidValuesBinding();

    private String m_role;
    private String m_roleDescription;
    private boolean m_newRole;
    private String m_code;
    private String m_description;
    private String m_uses = "";
    private String m_warnings = "";
    private String m_globalWarning = "";

    // simulatore
    private String s_type;
    private String s_group;
    private String s_plant;
    private String s_sloc;
    private String s_amount;
    private String s_currency = "EUR";
    private LocalDate s_date;
    private String s_outcome;
    private boolean s_assigned;

    public AssignmentsUI() {
        SessionGuard.requireUser("/Assignments.xml");
    }

    @Override public String getPageName() { return "/Assignments.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.AssignmentsUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static AssignmentService svc() { return EwfApp.get().assignments(); }

    public void prepare(IBackToMenu back) {
        m_back = back;
        try {
            m_typesVVB.clear();
            for (Choice c : EwfApp.get().designer().processTypes(uid()))
                m_typesVVB.addValidValue(c.code(), c.code() + " – " + c.text());
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reloadRoles();
    }

    private void reloadRoles() {
        m_roles.getItems().clear();
        try {
            for (RoleRow r : svc().roles(uid())) {
                RoleItem it = new RoleItem(r);
                m_roles.getItems().add(it);
                if (r.code().equals(m_role)) {
                    m_roles.selectItem(it);
                    m_roleDescription = r.description();
                }
            }
            List<String> orphan = svc().rolesWithoutRules(uid());
            m_globalWarning = orphan.isEmpty() ? ""
                    : "Ruoli usati in processi attivi senza nessuna regola (i loro task vanno al team di riserva): "
                    + String.join(", ", orphan);
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reloadRole();
    }

    private void reloadRole() {
        m_rules.getItems().clear();
        m_uses = "";
        m_warnings = "";
        if (m_role == null) return;
        try {
            for (RuleRow r : svc().rules(uid(), m_role)) m_rules.getItems().add(new RuleItem(r));
            StringBuilder u = new StringBuilder();
            for (RoleUse x : svc().roleUses(uid(), m_role)) {
                if (u.length() > 0) u.append(" · ");
                u.append(x.processType()).append(" v").append(x.versionNo())
                 .append("DRAFT".equals(x.status()) ? " (bozza)" : "").append(" step ").append(x.stepNo())
                 .append(x.option() == null ? "" : "/" + x.option()).append(": ").append(x.taskText());
            }
            m_uses = u.length() == 0 ? "Non usato nelle versioni attive o in bozza." : "Usato in: " + u;
            m_warnings = String.join("\n", svc().warnings(uid(), m_role));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private void selectRole(RoleRow r) {
        m_role = r.code();
        m_roleDescription = r.description();
        m_newRole = false;
        m_code = r.code();
        m_description = r.description();
        m_sim.getItems().clear();
        s_outcome = null;
        reloadRole();
    }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) { if (m_back != null) m_back.backToMenu(); }

    public void onShowAssign(ActionEvent ae) {
        m_startMode = false;
        reloadRoles();
    }

    public void onShowStart(ActionEvent ae) {
        m_startMode = true;
        if (m_startRules == null) m_startRules = new StartRulesUI();
        m_startRules.prepare();
    }

    public boolean isAssignMode() { return !m_startMode; }
    public boolean isStartMode() { return m_startMode; }
    public StartRulesUI getStartRules() { return m_startRules; }
    public String getAssignTabColor() { return m_startMode ? null : "#BBDEFB"; }
    public String getStartTabColor() { return m_startMode ? "#BBDEFB" : null; }

    public void onNewRole(ActionEvent ae) {
        m_newRole = true;
        m_code = null;
        m_description = null;
    }

    public void onSaveRole(ActionEvent ae) {
        try {
            svc().saveRole(uid(), m_code, m_description, m_newRole);
            if (m_newRole) m_role = m_code.trim().toUpperCase();
            m_newRole = false;
            Statusbar.outputSuccess("Ruolo salvato");
            reloadRoles();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onDeleteRole(ActionEvent ae) {
        if (m_role == null || m_newRole) return;
        String role = m_role;
        YESNOPopup.createInstance("Elimina ruolo", "Eliminare il ruolo " + role + "?", new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    svc().deleteRole(uid(), role);
                    m_role = null;
                    m_code = null;
                    m_description = null;
                    reloadRoles();
                } catch (ServiceException e) {
                    Statusbar.outputError(e.getMessage());
                }
            }
            @Override public void reactOnNo() { }
        });
    }

    public void onNewRule(ActionEvent ae) { openRule(null); }

    private void openRule(RuleRow r) {
        if (m_role == null) return;
        RuleEditUI ui = new RuleEditUI();
        ui.prepare(m_role, r, m_typesVVB, new TaskEditUI.IListener() {
            @Override public void saved() {
                closePopup(ui);
                reloadRoles();
            }
            @Override public void cancelled() { closePopup(ui); }
        });
        openModalPopup(ui, (r == null ? "Nuova regola" : "Regola " + r.id()) + " – ruolo " + m_role, 640, 470,
                new ModalPopup.IModalPopupListener() {
                    @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
                });
    }

    public void onSimulate(ActionEvent ae) {
        if (m_role == null) return;
        BigDecimal amount = null;
        if (s_amount != null && !s_amount.isBlank()) {
            try {
                amount = new BigDecimal(s_amount.trim().replace(".", "").replace(',', '.'));
            } catch (NumberFormatException e) {
                Statusbar.outputError("Importo non valido");
                return;
            }
        }
        try {
            SimResult r = svc().simulate(uid(), new SimRequest(s_type, m_role, s_group, s_plant, s_sloc, amount, s_currency, s_date));
            s_outcome = r.outcome();
            s_assigned = r.assigned();
            m_sim.getItems().clear();
            for (SimLine l : r.lines()) m_sim.getItems().add(new SimItem(l));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<RoleItem> getRoles() { return m_roles; }
    public FIXGRIDListBinding<RuleItem> getRules() { return m_rules; }
    public FIXGRIDListBinding<SimItem> getSim() { return m_sim; }
    public ValidValuesBinding getTypesVVB() { return m_typesVVB; }
    public boolean isRoleSelected() { return m_role != null && !m_newRole; }
    public boolean isNoRole() { return !isRoleSelected(); }
    public boolean isCodeEditable() { return m_newRole; }
    public String getRoleTitle() { return m_role == null ? "" : "Ruolo " + m_role + " – " + m_roleDescription; }
    public String getRoleFormTitle() { return m_newRole ? "Nuovo ruolo" : m_role == null ? "Ruolo" : "Ruolo " + m_role; }
    public String getCode() { return m_code; }
    public void setCode(String v) { if (m_newRole) m_code = v; }
    public String getDescription() { return m_description; }
    public void setDescription(String v) { m_description = v; }
    public String getUses() { return m_uses; }
    public String getWarnings() { return m_warnings; }
    public boolean isWithWarnings() { return isRoleSelected() && !m_warnings.isEmpty(); }
    public String getGlobalWarning() { return m_globalWarning; }
    public boolean isWithGlobalWarning() { return !m_globalWarning.isEmpty(); }

    public String getSimType() { return s_type; }
    public void setSimType(String v) { s_type = v == null || v.isBlank() ? null : v; }
    public String getSimGroup() { return s_group; }
    public void setSimGroup(String v) { s_group = v; }
    public String getSimPlant() { return s_plant; }
    public void setSimPlant(String v) { s_plant = v; }
    public String getSimSloc() { return s_sloc; }
    public void setSimSloc(String v) { s_sloc = v; }
    public String getSimAmount() { return s_amount; }
    public void setSimAmount(String v) { s_amount = v; }
    public String getSimCurrency() { return s_currency; }
    public void setSimCurrency(String v) { s_currency = v; }
    public LocalDate getSimDate() { return s_date; }
    public void setSimDate(LocalDate v) { s_date = v; }
    public String getSimOutcome() { return s_outcome; }
    public boolean isWithSimOutcome() { return s_outcome != null; }
    public String getSimOutcomeColor() { return s_assigned ? "#2E7D32" : "#E65100"; }

    // ------------------------------------------------------------------ righe

    static String any(String v) { return v == null ? "tutti" : v; }

    static String amount(RuleRow r) {
        if (r.amountFrom() == null && r.amountTo() == null) return "qualsiasi";
        String cur = r.currency() == null ? "" : " " + r.currency();
        if (r.amountFrom() == null) return "fino a " + num(r.amountTo()) + cur;
        if (r.amountTo() == null) return "da " + num(r.amountFrom()) + cur;
        return num(r.amountFrom()) + " – " + num(r.amountTo()) + cur;
    }

    static String num(BigDecimal v) {
        return new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ITALY)).format(v);
    }

    static String validity(RuleRow r) {
        if (r.validFrom() == null && r.validTo() == null) return "sempre";
        return (r.validFrom() == null ? "" : "dal " + DAY.format(r.validFrom()) + " ")
                + (r.validTo() == null ? "" : "al " + DAY.format(r.validTo()));
    }

    public class RoleItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final RoleRow r;
        RoleItem(RoleRow r) { this.r = r; }
        @Override public void onRowSelect() { selectRole(r); }
        public String getCode() { return r.code(); }
        public String getDescription() { return r.description(); }
        public String getRules() { return String.valueOf(r.rules()); }
        public String getRulesColor() { return r.rules() == 0 && r.usedInTasks() > 0 ? "#FFCDD2" : null; }
        public String getUsed() { return r.usedInTasks() == 0 ? "" : String.valueOf(r.usedInTasks()); }
    }

    public class RuleItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final RuleRow r;
        RuleItem(RuleRow r) { this.r = r; }
        public String getId() { return String.valueOf(r.id()); }
        public String getType() { return any(r.processType()); }
        public String getGroup() { return any(r.purchasingGroup()); }
        public String getPlant() { return any(r.plant()); }
        public String getSloc() { return any(r.storageLocation()); }
        public String getAmount() { return amount(r); }
        public String getValidity() { return validity(r); }
        public String getTarget() { return target(r); }
        public String getTargetColor() { return r.userId() != null && !r.userActive() ? "#FFCDD2" : null; }
        public String getSpecificity() { return String.valueOf(r.specificity()); }
        public void onEdit(ActionEvent ae) { openRule(r); }
        public void onDelete(ActionEvent ae) {
            YESNOPopup.createInstance("Elimina regola", "Eliminare la regola " + r.id() + " (riceve " + target(r) + ")?",
                    new YESNOPopup.IYesNoListener() {
                        @Override public void reactOnYes() {
                            try {
                                svc().deleteRule(uid(), r.id());
                                reloadRoles();
                            } catch (ServiceException e) {
                                Statusbar.outputError(e.getMessage());
                            }
                        }
                        @Override public void reactOnNo() { }
                    });
        }
    }

    static String target(RuleRow r) {
        if (r.userId() != null && r.teamCode() != null) return r.userName() + " + team " + r.teamCode();
        if (r.userId() != null) return r.userName() + (r.userActive() ? "" : " (disattivato)");
        return "team " + r.teamCode();
    }

    public class SimItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final SimLine l;
        SimItem(SimLine l) { this.l = l; }
        public String getId() { return String.valueOf(l.rule().id()); }
        public String getCriteria() {
            RuleRow r = l.rule();
            StringBuilder b = new StringBuilder();
            if (r.processType() != null) b.append(r.processType()).append(' ');
            if (r.purchasingGroup() != null) b.append("gr.").append(r.purchasingGroup()).append(' ');
            if (r.plant() != null) b.append("div.").append(r.plant()).append(' ');
            if (r.storageLocation() != null) b.append("mag.").append(r.storageLocation()).append(' ');
            if (r.amountFrom() != null || r.amountTo() != null) b.append(amount(r));
            return b.length() == 0 ? "(generica)" : b.toString().trim();
        }
        public String getTarget() { return target(l.rule()); }
        public String getResult() {
            if (l.winner()) return "VINCE";
            return l.excludedBecause() == null ? "compatibile, ma meno specifica o piu' recente" : "esclusa: " + l.excludedBecause();
        }
        public String getResultColor() { return l.winner() ? "#C8E6C9" : l.excludedBecause() == null ? "#FFF59D" : null; }
    }
}
