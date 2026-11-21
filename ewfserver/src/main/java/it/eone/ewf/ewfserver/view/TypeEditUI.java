package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.DesignerService.TypeEdit;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;

/**
 * Nuovo tipo processo (con la prima versione vuota in bozza) o modifica delle proprieta' di uno esistente.
 * Il tipo documento si sceglie tra quelli esistenti o se ne crea uno nuovo, senza sistema di origine.
 */
@CCGenClass(expressionBase = "#{d.TypeEditUI}")
public class TypeEditUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    static final String NEW_DOC = "__NEW__";

    /** created: codice del tipo e id della bozza creata (solo per un tipo nuovo). */
    public interface IListener extends Serializable {
        void saved(String typeCode, Long draftId);
        void cancelled();
    }

    private IListener m_listener;
    private boolean m_new;
    private final ValidValuesBinding m_docTypesVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_scopeVVB = new ValidValuesBinding();

    private String m_code;
    private String m_description;
    private String m_docType;
    private String m_newDocCode;
    private String m_newDocDescription;
    private boolean m_manualStart;
    private boolean m_suspended;
    private String m_summary;
    private String m_columns;
    private String m_yellow = "6";
    private String m_red = "11";
    private String m_scope = "PROCESS";

    public TypeEditUI() {
        SessionGuard.requireUser("/TypeEdit.xml");
        m_scopeVVB.addValidValue("PROCESS", "in eWF");
        m_scopeVVB.addValidValue("BOTH", "nel sistema di origine e in eWF");
        m_scopeVVB.addValidValue("DOCUMENT", "solo nel sistema di origine");
    }

    @Override public String getPageName() { return "/TypeEdit.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.TypeEditUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static DesignerService designer() { return EwfApp.get().designer(); }

    /** code null = nuovo tipo processo. */
    public void prepare(String code, IListener listener) {
        m_listener = listener;
        m_new = code == null;
        try {
            m_docTypesVVB.clear();
            for (Choice c : designer().docTypes(uid())) m_docTypesVVB.addValidValue(c.code(), c.code() + " – " + c.text());
            if (m_new) m_docTypesVVB.addValidValue(NEW_DOC, "nuovo tipo documento…");
            if (m_new) return;
            TypeEdit t = designer().typeSettings(uid(), code);
            m_code = t.code();
            m_description = t.description();
            m_docType = t.docType();
            m_manualStart = t.manualStart();
            m_suspended = t.suspended();
            m_summary = t.listSummary();
            m_columns = t.listColumns();
            m_yellow = String.valueOf(t.slaYellowDays());
            m_red = String.valueOf(t.slaRedDays());
            m_scope = t.attachmentScope();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onSave(ActionEvent ae) {
        int y, r;
        try {
            y = Integer.parseInt(m_yellow.trim());
            r = Integer.parseInt(m_red.trim());
        } catch (RuntimeException e) {
            Statusbar.outputError("Giorni del semaforo non validi");
            return;
        }
        boolean newDoc = NEW_DOC.equals(m_docType);
        String docType = newDoc ? m_newDocCode : m_docType;
        if (m_new && (docType == null || docType.isBlank())) {
            Statusbar.outputError(newDoc ? "Indicare il codice del nuovo tipo documento" : "Scegliere il tipo documento");
            return;
        }
        TypeEdit t = new TypeEdit(m_code, m_description, docType, newDoc ? m_newDocDescription : null, m_manualStart,
                m_suspended, m_summary, m_columns, y, r, m_scope);
        try {
            if (m_new) {
                long draft = designer().createProcessType(uid(), t);
                if (m_listener != null) m_listener.saved(m_code.trim().toUpperCase(), draft);
            } else {
                designer().saveProcessType(uid(), t);
                if (m_listener != null) m_listener.saved(m_code, null);
            }
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onCancel(ActionEvent ae) { if (m_listener != null) m_listener.cancelled(); }

    public void onNoop(ActionEvent ae) { }

    public ValidValuesBinding getDocTypesVVB() { return m_docTypesVVB; }
    public ValidValuesBinding getScopeVVB() { return m_scopeVVB; }
    public boolean isNewType() { return m_new; }
    public boolean isNewDocType() { return m_new && NEW_DOC.equals(m_docType); }
    public String getDocTypeText() { return "Tipo documento: " + m_docType; }
    public boolean isExistingType() { return !m_new; }

    public String getCode() { return m_code; }
    public void setCode(String v) { if (m_new) m_code = v; }
    public String getDescription() { return m_description; }
    public void setDescription(String v) { m_description = v; }
    public String getDocType() { return m_docType; }
    public void setDocType(String v) { if (m_new) m_docType = v; }
    public String getNewDocCode() { return m_newDocCode; }
    public void setNewDocCode(String v) { m_newDocCode = v; }
    public String getNewDocDescription() { return m_newDocDescription; }
    public void setNewDocDescription(String v) { m_newDocDescription = v; }
    public boolean isManualStart() { return m_manualStart; }
    public void setManualStart(boolean v) { m_manualStart = v; }
    public boolean isSuspended() { return m_suspended; }
    public void setSuspended(boolean v) { m_suspended = v; }
    public String getSummary() { return m_summary; }
    public void setSummary(String v) { m_summary = v; }
    public String getColumns() { return m_columns; }
    public void setColumns(String v) { m_columns = v; }
    public String getYellow() { return m_yellow; }
    public void setYellow(String v) { m_yellow = v; }
    public String getRed() { return m_red; }
    public void setRed(String v) { m_red = v; }
    public String getScope() { return m_scope; }
    public void setScope(String v) { m_scope = v == null ? "PROCESS" : v; }
}
