package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;

/** Annullamento di un processo in corso da parte dell'amministratore: motivo obbligatorio (finisce nell'audit). */
@CCGenClass(expressionBase = "#{d.CancelProcessUI}")
public class CancelProcessUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnDone(boolean cancelled);
    }

    private long m_processId;
    private String m_title = "";
    private String m_note;
    private IListener m_listener;

    public CancelProcessUI() {
        SessionGuard.requireUser("/CancelProcess.xml");
    }

    @Override public String getPageName() { return "/CancelProcess.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.CancelProcessUI}"; }

    public void prepare(long processId, String title, IListener listener) {
        m_processId = processId;
        m_title = title;
        m_listener = listener;
    }

    public void onConfirm(ActionEvent ae) {
        try {
            EwfApp.get().admin().cancel(EwfSession.instance().userId(), m_processId, m_note);
            Statusbar.outputSuccess("Processo " + m_processId + " annullato");
            if (m_listener != null) m_listener.reactOnDone(true);
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onAbort(ActionEvent ae) {
        if (m_listener != null) m_listener.reactOnDone(false);
    }

    public String getTitle() { return m_title; }
    public String getNote() { return m_note; }
    public void setNote(String v) { m_note = v; }
}
