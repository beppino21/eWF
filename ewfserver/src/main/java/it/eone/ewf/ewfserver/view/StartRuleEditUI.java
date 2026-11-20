package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.StartRuleService.RuleEdit;
import it.eone.ewf.service.StartRuleService.RuleRow;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.util.List;

/** Nuova regola di avvio o modifica: processo, priorita', attiva, condizioni "attributo = valore". */
@CCGenClass(expressionBase = "#{d.StartRuleEditUI}")
public class StartRuleEditUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private TaskEditUI.IListener m_listener;
    private String m_docType;
    private Long m_id;
    private final ValidValuesBinding m_typesVVB = new ValidValuesBinding();
    private String m_process;
    private String m_priority = "0";
    private boolean m_active = true;
    private String m_conditions;
    private String m_hint = "";

    public StartRuleEditUI() {
        SessionGuard.requireUser("/StartRuleEdit.xml");
    }

    @Override public String getPageName() { return "/StartRuleEdit.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.StartRuleEditUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }

    public void prepare(String docType, RuleRow r, TaskEditUI.IListener listener) {
        m_docType = docType;
        m_listener = listener;
        try {
            for (Choice c : StartRulesUI.svc().processTypes(uid(), docType))
                m_typesVVB.addValidValue(c.code(), c.code() + " – " + c.text());
            List<String> keys = StartRulesUI.svc().knownKeys(uid(), docType);
            m_hint = "Una condizione per riga, \"attributo = valore\"; \"*\" = qualsiasi valore; nessuna condizione = ogni"
                    + " documento." + (keys.isEmpty() ? "" : " Attributi gia' usati: " + String.join(", ", keys) + ".")
                    + " Gli attributi di un documento vero si vedono con \"Leggi dal sistema di origine\" nel simulatore.";
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        if (r == null) return;
        m_id = r.id();
        m_process = r.processType();
        m_priority = String.valueOf(r.priority());
        m_active = r.active();
        m_conditions = StartRulesUI.format(r.conditions());
    }

    public void onSave(ActionEvent ae) {
        int prio;
        try {
            prio = m_priority == null || m_priority.isBlank() ? 0 : Integer.parseInt(m_priority.trim());
        } catch (NumberFormatException e) {
            Statusbar.outputError("Priorita' non valida (un numero intero; vince la piu' alta)");
            return;
        }
        try {
            StartRulesUI.svc().saveRule(uid(), new RuleEdit(m_id, m_docType, StartRulesUI.parse(m_conditions), prio, m_process, m_active));
            if (m_listener != null) m_listener.saved();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onCancel(ActionEvent ae) { if (m_listener != null) m_listener.cancelled(); }

    public ValidValuesBinding getTypesVVB() { return m_typesVVB; }
    public String getProcess() { return m_process; }
    public void setProcess(String v) { m_process = v == null || v.isBlank() ? null : v; }
    public String getPriority() { return m_priority; }
    public void setPriority(String v) { m_priority = v; }
    public boolean isActive() { return m_active; }
    public void setActive(boolean v) { m_active = v; }
    public String getConditions() { return m_conditions; }
    public void setConditions(String v) { m_conditions = v; }
    public String getHint() { return m_hint; }
    public String getDocTypeText() { return "Tipo documento: " + m_docType; }
}
