package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.DesignerService.VersionEdit;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;

/** Proprieta' di una bozza: azione di fine processo con i suoi parametri, nota. */
@CCGenClass(expressionBase = "#{d.VersionEditUI}")
public class VersionEditUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private TaskEditUI.IListener m_listener;
    private long m_versionId;
    private final ValidValuesBinding m_handlersVVB = new ValidValuesBinding();
    private String m_handler;
    private String m_config;
    private String m_note;

    public VersionEditUI() {
        SessionGuard.requireUser("/VersionEdit.xml");
    }

    @Override public String getPageName() { return "/VersionEdit.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.VersionEditUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static DesignerService designer() { return EwfApp.get().designer(); }

    public void prepare(long versionId, TaskEditUI.IListener listener) {
        m_versionId = versionId;
        m_listener = listener;
        try {
            m_handlersVVB.clear();
            for (Choice c : designer().handlers(uid(), "PROCESS_END"))
                m_handlersVVB.addValidValue(c.code(), c.text() == null ? c.code() : c.code() + " – " + c.text());
            VersionEdit v = designer().versionSettings(uid(), versionId);
            m_handler = v.endHandlerCode();
            m_config = v.endHandlerConfig();
            m_note = v.note();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onSave(ActionEvent ae) {
        try {
            designer().saveVersion(uid(), m_versionId, new VersionEdit(m_handler, m_config, m_note));
            if (m_listener != null) m_listener.saved();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onCancel(ActionEvent ae) { if (m_listener != null) m_listener.cancelled(); }

    public ValidValuesBinding getHandlersVVB() { return m_handlersVVB; }
    public String getHandler() { return m_handler; }
    public void setHandler(String v) { m_handler = v == null || v.isBlank() ? null : v; }
    public String getConfig() { return m_config; }
    public void setConfig(String v) { m_config = v; }
    public String getNote() { return m_note; }
    public void setNote(String v) { m_note = v; }
}
