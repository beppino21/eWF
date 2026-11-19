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

    private boolean m_admin;

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
        m_admin = isAdmin(s.userId());
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
        showMenu();
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
        m_admin = false;
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
                @Override public void reactOnMenu() { showMenu(); }
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

    /** Dettaglio in sola consultazione, aperto da un elenco di processi: "« Lista" torna a quell'elenco. */
    private void showReadOnlyDetail(long processId, ProcessListUI list) {
        TaskDetailUI ui = new TaskDetailUI();
        ui.prepare(processId, new TaskDetailUI.IListener() {
            @Override public void reactOnBack() {
                if (list != null) {
                    list.reload();
                    m_contentUI = list;
                } else {
                    showMenu();
                }
            }
            @Override public void reactOnLogout() { logout(); }
        }, true);
        m_contentUI = ui;
    }

    private void showProcessList(ProcessListUI.Mode mode) {
        ProcessListUI ui = new ProcessListUI();
        ui.prepare(mode, new ProcessListUI.IListener() {
            @Override public void reactOnOpen(long processId) { showReadOnlyDetail(processId, ui); }
            @Override public void backToMenu() { showMenu(); }
        });
        m_contentUI = ui;
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        MenuUI ui = new MenuUI();
        ui.prepare(m_admin, errorCount(), new MenuUI.IListener() {
            @Override public void reactOnMenuChoice(String choice) { open(choice); }
            @Override public void reactOnLogout() { logout(); }
        });
        m_contentUI = ui;
    }

    private void open(String choice) {
        IBackToMenu back = this::showMenu;
        switch (choice) {
            case MenuUI.TASKS -> showWorklist();
            case MenuUI.AUTO_SUBSTITUTION -> {
                AutoSubstitutionUI ui = new AutoSubstitutionUI();
                ui.prepare(back);
                m_contentUI = ui;
            }
            case MenuUI.SUBSTITUTIONS -> {
                SubstitutionsUI ui = new SubstitutionsUI();
                ui.setBack(back);
                m_contentUI = ui;
            }
            case MenuUI.ERRORS -> {
                if (!m_admin) return;
                ErrorsUI ui = new ErrorsUI();
                ui.prepare(n -> { }, back);
                m_contentUI = ui;
            }
            case MenuUI.PARTICIPATED -> showProcessList(ProcessListUI.Mode.PARTICIPATED);
            case MenuUI.ADMIN_PROCESSES -> { if (m_admin) showProcessList(ProcessListUI.Mode.ADMIN); }
            case MenuUI.FORCE_START -> {
                if (!m_admin) return;
                ForceStartUI ui = new ForceStartUI();
                ui.prepare(new ForceStartUI.IListener() {
                    @Override public void backToMenu() { showMenu(); }
                    @Override public void reactOnOpen(long processId) { showReadOnlyDetail(processId, null); }
                });
                m_contentUI = ui;
            }
            case MenuUI.ADMIN_SUBSTITUTIONS -> {
                if (!m_admin) return;
                AdminSubstitutionsUI ui = new AdminSubstitutionsUI();
                ui.prepare(back);
                m_contentUI = ui;
            }
            case MenuUI.TEAMS -> {
                if (!m_admin) return;
                TeamsUI ui = new TeamsUI();
                ui.prepare(back);
                m_contentUI = ui;
            }
            case MenuUI.USERS -> {
                if (!m_admin) return;
                UsersUI ui = new UsersUI();
                ui.prepare(back);
                m_contentUI = ui;
            }
            case MenuUI.PASSWORD -> {
                LogonUI ui = new LogonUI();
                ui.prepareChange(EwfSession.instance().user(), changed -> showMenu());
                m_contentUI = ui;
            }
            default -> { }
        }
    }

    private static boolean isAdmin(long userId) {
        try {
            return EwfApp.get().admin().isAdmin(userId);
        } catch (RuntimeException e) {
            System.err.println("[OutestUI] Verifica amministratore non riuscita: " + e.getMessage());
            return false;
        }
    }

    private int errorCount() {
        if (!m_admin) return 0;
        try {
            return EwfApp.get().admin().processesInError(EwfSession.instance().userId()).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
