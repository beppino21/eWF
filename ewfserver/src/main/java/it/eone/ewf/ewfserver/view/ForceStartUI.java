package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.AdminService;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;

/**
 * Avvio forzato di un processo (amministratori): tipo documento, processo ammesso per quel tipo, documento.
 * Il motore legge il documento nel sistema di origine e rifiuta doppioni e documenti inesistenti.
 */
@CCGenClass(expressionBase = "#{d.ForceStartUI}")
public class ForceStartUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void backToMenu();
        void reactOnOpen(long processId);
    }

    private IListener m_listener;
    private final ValidValuesBinding m_docTypesVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_typesVVB = new ValidValuesBinding();
    private String m_docType;
    private String m_processType;
    private String m_document;
    private Long m_started;
    private String m_result;

    public ForceStartUI() {
        SessionGuard.requireUser("/ForceStart.xml");
    }

    @Override public String getPageName() { return "/ForceStart.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.ForceStartUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static AdminService admin() { return EwfApp.get().admin(); }

    public void prepare(IListener listener) {
        m_listener = listener;
        try {
            m_docTypesVVB.clear();
            for (String d : admin().docTypes(uid())) m_docTypesVVB.addValidValue(d, d);
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        loadTypes();
    }

    private void loadTypes() {
        m_typesVVB.clear();
        if (m_docType == null) { m_processType = null; return; }
        try {
            var types = admin().startableTypes(uid(), m_docType);
            for (AdminService.StartableType t : types)
                m_typesVVB.addValidValue(t.code(), t.description() + " (" + t.code() + ")");
            if (m_processType != null && types.stream().noneMatch(t -> t.code().equals(m_processType))) m_processType = null;
            if (m_processType == null && types.size() == 1) m_processType = types.get(0).code();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onBackToMenu(ActionEvent ae) { if (m_listener != null) m_listener.backToMenu(); }

    public void onDocTypeChanged(ActionEvent ae) { loadTypes(); }

    public void onStart(ActionEvent ae) {
        m_result = null;
        m_started = null;
        try {
            m_started = admin().forceStart(uid(), m_docType, m_document, m_processType);
            m_result = "Avviato il processo " + m_started + " per il documento " + m_document.trim();
            Statusbar.outputSuccess(m_result);
            m_document = null;
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onOpenStarted(ActionEvent ae) {
        if (m_started != null && m_listener != null) m_listener.reactOnOpen(m_started);
    }

    public ValidValuesBinding getDocTypesVVB() { return m_docTypesVVB; }
    public ValidValuesBinding getTypesVVB() { return m_typesVVB; }
    public String getDocType() { return m_docType; }
    public void setDocType(String v) { m_docType = v == null || v.isBlank() ? null : v; }
    public String getProcessType() { return m_processType; }
    public void setProcessType(String v) { m_processType = v == null || v.isBlank() ? null : v; }
    public String getDocument() { return m_document; }
    public void setDocument(String v) { m_document = v; }
    public boolean isHasResult() { return m_result != null; }
    public String getResult() { return m_result; }
}
