package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.SubstitutionService;
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
import java.util.List;
import java.util.Optional;

/**
 * Unica pagina avviabile dal browser (system.xml, riscstarter): governa l'accesso, come nel ticketing.
 * Mostra il logon e, dopo l'accesso, lista e dettaglio dentro rowpagebeaninclude.
 *
 * Collegamento diretto (mail di avviso): .../Outest.risc?task=8000000027 apre il task dopo il logon.
 *
 * Telefono: se il browser si dichiara "Mobi" (iPhone, telefoni Android) dopo il logon si apre la versione
 * mobile (MobileTasksUI / MobileTaskUI) al posto del menu. ?mobile=1 la forza, ?mobile=0 la esclude;
 * dalla lista mobile "Versione completa" passa al menu desktop.
 */
@CCGenClass(expressionBase = "#{d.OutestUI}")
public class OutestUI extends WorkpageDispatchedPageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private IPageBean m_contentUI;
    private WorklistUI m_worklistUI;
    private Long m_pendingTask;
    private boolean m_mobile;

    private boolean m_admin;
    private String m_substituting = "";   // "sostituisce oggi: ..." (aggiornato al logon e a ogni ritorno al menu)

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
            String m = req == null ? null : req.getParameter("mobile");
            String ua = req == null ? null : req.getHeader("User-Agent");
            m_mobile = "1".equals(m) || (!"0".equals(m) && ua != null && ua.contains("Mobi"));
        } catch (Exception e) {
            System.err.println("[OutestUI] Parametro task non leggibile: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ logon / logout

    private void showLogon() {
        LogonUI ui = new LogonUI();
        ui.prepare(this::afterLogon);
        ui.setMobile(m_mobile);
        m_contentUI = ui;
    }

    private void afterLogon(AuthService.Session s) {
        EwfSession.instance().login(s);
        confirmExit(true);
        m_worklistUI = null;
        m_admin = isAdmin(s.userId());
        refreshSubstituting();
        Long task = m_pendingTask;
        m_pendingTask = null;
        if (task != null) {
            Optional<WorkItem> w = findTask(task);
            if (w.isPresent()) {
                if (m_mobile) showMobileDetail(w.get().processId()); else showDetail(w.get().processId());
                return;
            }
            if (m_mobile) showMobileTasks(); else showWorklist();
            Statusbar.outputWarning("L'attivita' " + task + " non e' piu' tra le tue: forse e' gia' stata completata.");
            return;
        }
        if (m_mobile) showMobileTasks(); else showMenu();
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
        m_mobileTasksUI = null;
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

    // ------------------------------------------------------------------ versione mobile

    private MobileTasksUI m_mobileTasksUI;

    private void showMobileTasks() {
        if (m_mobileTasksUI == null) {
            m_mobileTasksUI = new MobileTasksUI();
            m_mobileTasksUI.prepare(new MobileTasksUI.IListener() {
                @Override public void reactOnOpen(long processId) { showMobileDetail(processId); }
                @Override public void reactOnLogout() { logout(); }
                @Override public void reactOnDesktop() {
                    m_mobile = false;
                    m_mobileTasksUI = null;
                    showMenu();
                }
            });
        } else {
            m_mobileTasksUI.reload();
        }
        m_contentUI = m_mobileTasksUI;
    }

    private void showMobileDetail(long processId) {
        MobileTaskUI ui = new MobileTaskUI();
        ui.prepare(processId, this::showMobileTasks);
        m_contentUI = ui;
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

    private void refreshSubstituting() {
        m_substituting = "";
        try {
            List<String> names = new java.util.ArrayList<>();
            for (SubstitutionService.Substitution x : EwfApp.get().substitutions().asSubstitute(EwfSession.instance().userId()))
                if (x.activeToday()) names.add(x.userName());
            if (!names.isEmpty()) m_substituting = "sostituisce oggi " + String.join(", ", names);
        } catch (RuntimeException e) {
            // solo un'informazione: se non si legge, non si mostra
        }
    }

    /**
     * Chi sta operando: nome, login, amministratore, per chi fa da sostituto. Sempre visibile tranne che nel
     * logon e nel menu (che saluta gia' l'utente). Lo stesso nome va nel titolo della scheda del browser.
     */
    public boolean isUserBarVisible() {
        return EwfSession.instance().loggedIn() && !(m_contentUI instanceof MenuUI) && !(m_contentUI instanceof LogonUI)
                && !(m_contentUI instanceof MobileTasksUI) && !(m_contentUI instanceof MobileTaskUI);
    }

    public String getUserBar() {
        AuthService.Session u = EwfSession.instance().user();
        if (u == null) return "";
        StringBuilder b = new StringBuilder("\u25cf ").append(u.displayName()).append(" (").append(u.login()).append(')');
        if (m_admin) b.append(" \u00b7 amministratore");
        if (!m_substituting.isEmpty()) b.append(" \u00b7 ").append(m_substituting);
        return b.toString();
    }

    public String getWindowTitle() {
        AuthService.Session u = EwfSession.instance().user();
        return u == null ? "eWF" : "eWF \u2013 " + u.login();
    }

    private void showMenu() {
        refreshSubstituting();
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
            case MenuUI.ASSIGNMENTS -> {
                if (!m_admin) return;
                AssignmentsUI ui = new AssignmentsUI();
                ui.prepare(back);
                m_contentUI = ui;
            }
            case MenuUI.DESIGNER -> {
                if (!m_admin) return;
                DesignerUI ui = new DesignerUI();
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
