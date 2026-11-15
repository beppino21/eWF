package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.WorkItem;
import it.eone.ewf.service.Views.WorklistFilter;
import it.eone.ewf.service.auth.AuthService;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.IPageBean;
import org.eclnt.jsfserver.util.HttpSessionAccess;
import org.eclnt.workplace.IWorkpageDispatcher;
import org.eclnt.workplace.WorkpageDispatchedPageBean;

import java.io.Serializable;
import java.util.Optional;

/**
 * Unica pagina avviabile dal browser (system.xml, riscstarter): governa l'accesso, come nel ticketing.
 * Mostra il logon e, dopo l'accesso, lista e dettaglio dentro rowpagebeaninclude.
 *
 * Collegamento diretto (mail di avviso): .../Outest.risc?task=8000000027 apre il task dopo il logon.
 */
@CCGenClass(expressionBase = "#{d.OutestUI}")
public class OutestUI extends WorkpageDispatchedPageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private IPageBean m_contentUI;
    private WorklistUI m_worklistUI;
    private Long m_pendingTask;

    public OutestUI(IWorkpageDispatcher dispatcher) {
        super(dispatcher);
        readDeepLink();
        showLogon();
    }

    @Override public String getPageName() { return "/Outest.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.OutestUI}"; }

    public IPageBean getContentUI() { return m_contentUI; }

    /** ?task=... sulla richiesta iniziale (l'unica vera richiesta HTTP verso Outest). */
    private void readDeepLink() {
        try {
            jakarta.servlet.http.HttpServletRequest req = HttpSessionAccess.getCurrentRequest();
            String t = req == null ? null : req.getParameter("task");
            if (t != null && t.trim().matches("\\d{1,18}")) m_pendingTask = Long.parseLong(t.trim());
        } catch (Exception e) {
            System.err.println("[OutestUI] Parametro task non leggibile: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ logon / logout

    private void showLogon() {
        LogonUI ui = new LogonUI();
        ui.prepare(this::afterLogon);
        m_contentUI = ui;
    }

    private void afterLogon(AuthService.Session s) {
        EwfSession.instance().login(s);
        confirmExit(true);
        m_worklistUI = null;
        Long task = m_pendingTask;
        m_pendingTask = null;
        if (task != null) {
            Optional<WorkItem> w = findTask(task);
            if (w.isPresent()) {
                showDetail(w.get().processId());
                return;
            }
            showWorklist();
            Statusbar.outputWarning("L'attivita' " + task + " non e' piu' tra le tue: forse e' gia' stata completata.");
            return;
        }
        showWorklist();
    }

    private Optional<WorkItem> findTask(long taskId) {
        try {
            return EwfApp.get().service().worklist(EwfSession.instance().userId(), WorklistFilter.all()).items().stream()
                    .filter(i -> i.taskId() == taskId).findFirst();
        } catch (ServiceException e) {
            return Optional.empty();
        }
    }

    private void logout() {
        EwfSession.instance().logout();
        confirmExit(false);
        m_worklistUI = null;
        showLogon();
    }

    /** Conferma del browser su Indietro/F5/chiusura, solo mentre si e' dentro l'applicazione. */
    private void confirmExit(boolean on) {
        try {
            org.eclnt.jsfserver.defaultscreens.Client.instance().setConfirmexit(on);
        } catch (Throwable t) {
            System.err.println("[OutestUI] confirmexit non impostabile: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ lista e dettaglio

    private void showWorklist() {
        if (m_worklistUI == null) {
            m_worklistUI = new WorklistUI();
            m_worklistUI.prepare(new WorklistUI.IListener() {
                @Override public void reactOnOpen(long processId) { showDetail(processId); }
                @Override public void reactOnLogout() { logout(); }
            });
        } else {
            m_worklistUI.reload();
        }
        m_contentUI = m_worklistUI;
    }

    private void showDetail(long processId) {
        TaskDetailUI ui = new TaskDetailUI();
        ui.prepare(processId, new TaskDetailUI.IListener() {
            @Override public void reactOnBack() { showWorklist(); }
            @Override public void reactOnLogout() { logout(); }
        });
        m_contentUI = ui;
    }
}
