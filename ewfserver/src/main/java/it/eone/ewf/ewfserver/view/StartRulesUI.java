package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.StartRuleService;
import it.eone.ewf.service.StartRuleService.RuleRow;
import it.eone.ewf.service.StartRuleService.SimLine;
import it.eone.ewf.service.StartRuleService.SimResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regole di avvio (dentro "Assegnazioni e avvio"): per tipo documento, quale processo parte; con avvisi e il
 * simulatore "Quale processo parte?", anche su un documento vero letto dal sistema di origine.
 */
@CCGenClass(expressionBase = "#{d.StartRulesUI}")
public class StartRulesUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ValidValuesBinding m_docTypesVVB = new ValidValuesBinding();
    private final FIXGRIDListBinding<RuleItem> m_rules = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<SimItem> m_sim = new FIXGRIDListBinding<>();
    private String m_docType;
    private String m_warnings = "";
    private String m_docKey;
    private String m_attributes;
    private String m_outcome;
    private boolean m_starts;

    public StartRulesUI() {
        SessionGuard.requireUser("/StartRules.xml");
    }

    @Override public String getPageName() { return "/StartRules.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.StartRulesUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    static StartRuleService svc() { return EwfApp.get().startRules(); }

    public void prepare() {
        try {
            m_docTypesVVB.clear();
            List<Choice> types = svc().docTypes(uid());
            for (Choice c : types) m_docTypesVVB.addValidValue(c.code(), c.code() + " – " + c.text());
            if (m_docType == null && !types.isEmpty()) m_docType = types.get(0).code();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reload();
    }

    private void reload() {
        m_rules.getItems().clear();
        m_warnings = "";
        if (m_docType == null) return;
        try {
            for (RuleRow r : svc().rules(uid(), m_docType)) m_rules.getItems().add(new RuleItem(r));
            m_warnings = String.join("\n", svc().warnings(uid(), m_docType));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ azioni

    public void onDocTypeChanged(ActionEvent ae) {
        m_sim.getItems().clear();
        m_outcome = null;
        reload();
    }

    public void onNewRule(ActionEvent ae) { openRule(null); }

    private void openRule(RuleRow r) {
        if (m_docType == null) return;
        StartRuleEditUI ui = new StartRuleEditUI();
        ui.prepare(m_docType, r, new TaskEditUI.IListener() {
            @Override public void saved() {
                closePopup(ui);
                reload();
            }
            @Override public void cancelled() { closePopup(ui); }
        });
        openModalPopup(ui, (r == null ? "Nuova regola di avvio" : "Regola di avvio " + r.id()) + " – " + m_docType,
                620, 470, new ModalPopup.IModalPopupListener() {
                    @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
                });
    }

    public void onReadDocument(ActionEvent ae) {
        try {
            Map<String, String> ctx = svc().documentContext(uid(), m_docType, m_docKey);
            m_attributes = format(ctx);
            Statusbar.outputSuccess("Letti " + ctx.size() + " attributi del documento " + m_docKey.trim());
            onSimulate(null);
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onSimulate(ActionEvent ae) {
        try {
            SimResult r = svc().simulate(uid(), m_docType, parse(m_attributes));
            m_outcome = r.outcome();
            m_starts = r.starts();
            m_sim.getItems().clear();
            for (SimLine l : r.lines()) m_sim.getItems().add(new SimItem(l));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    /** "attributo = valore", una coppia per riga; righe vuote e # ignorate. */
    static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int i = l.indexOf('=');
            if (i < 0) i = l.indexOf(':');
            if (i <= 0) throw new ServiceException(ServiceException.Code.INVALID, "Riga senza '=': " + l);
            out.put(l.substring(0, i).trim(), l.substring(i + 1).trim());
        }
        return out;
    }

    static String format(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> b.append(k).append(" = ").append(v).append('\n'));
        return b.toString();
    }

    static String conditions(Map<String, String> c) {
        if (c.isEmpty()) return "(qualsiasi documento)";
        StringBuilder b = new StringBuilder();
        c.forEach((k, v) -> b.append(b.length() == 0 ? "" : " · ").append(k).append(" = ").append(v));
        return b.toString();
    }

    // ------------------------------------------------------------------ binding

    public ValidValuesBinding getDocTypesVVB() { return m_docTypesVVB; }
    public FIXGRIDListBinding<RuleItem> getRules() { return m_rules; }
    public FIXGRIDListBinding<SimItem> getSim() { return m_sim; }
    public String getDocType() { return m_docType; }
    public void setDocType(String v) { m_docType = v; }
    public String getWarnings() { return m_warnings; }
    public boolean isWithWarnings() { return !m_warnings.isEmpty(); }
    public String getDocKey() { return m_docKey; }
    public void setDocKey(String v) { m_docKey = v; }
    public String getAttributes() { return m_attributes; }
    public void setAttributes(String v) { m_attributes = v; }
    public String getOutcome() { return m_outcome; }
    public boolean isWithOutcome() { return m_outcome != null; }
    public String getOutcomeColor() { return m_starts ? "#2E7D32" : "#E65100"; }

    // ------------------------------------------------------------------ righe

    public class RuleItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final RuleRow r;
        RuleItem(RuleRow r) { this.r = r; }
        public String getId() { return String.valueOf(r.id()); }
        public String getActive() { return r.active() ? "sì" : "no"; }
        public String getActiveColor() { return r.active() ? "#C8E6C9" : "#E0E0E0"; }
        public String getPriority() { return String.valueOf(r.priority()); }
        public String getConditions() { return conditions(r.conditions()); }
        public String getProcess() { return r.processType() + " – " + r.processDescription(); }
        public String getProcessColor() { return r.active() && !r.startable() ? "#FFCDD2" : null; }
        public void onEdit(ActionEvent ae) { openRule(r); }
        public void onDelete(ActionEvent ae) {
            YESNOPopup.createInstance("Elimina regola", "Eliminare la regola di avvio " + r.id() + "?", new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    try {
                        svc().deleteRule(uid(), r.id());
                        reload();
                    } catch (ServiceException e) {
                        Statusbar.outputError(e.getMessage());
                    }
                }
                @Override public void reactOnNo() { }
            });
        }
    }

    public class SimItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final SimLine l;
        SimItem(SimLine l) { this.l = l; }
        public String getId() { return String.valueOf(l.rule().id()); }
        public String getConditions() { return "prio " + l.rule().priority() + " · " + conditions(l.rule().conditions()); }
        public String getProcess() { return l.rule().processType(); }
        public String getResult() {
            if (l.winner()) return "PARTE";
            return l.excludedBecause() == null ? "applicabile, ma con priorita' o condizioni minori" : "esclusa: " + l.excludedBecause();
        }
        public String getResultColor() { return l.winner() ? "#C8E6C9" : l.excludedBecause() == null ? "#FFF59D" : null; }
    }
}
