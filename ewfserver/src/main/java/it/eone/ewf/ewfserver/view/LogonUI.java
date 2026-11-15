package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.auth.AuthService;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;

/**
 * Logon con gli utenti eWF, a passi come nel ticketing:
 *  PASSWORD: utente e password;
 *  CHANGE:   cambio password, obbligatorio se la password e' provvisoria (resetPassword / PasswordTool),
 *            oppure volontario dal collegamento "Cambia password...".
 * La logica (hash, blocco dopo 5 tentativi, regole) e' in AuthService; i messaggi restano generici.
 * Le password non restano in memoria oltre la singola azione.
 */
@CCGenClass(expressionBase = "#{d.LogonUI}")
public class LogonUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void reactOnLogon(AuthService.Session session);
    }

    private static final String STEP_PASSWORD = "PASSWORD";
    private static final String STEP_CHANGE = "CHANGE";
    private static final long CHANGE_TIMEOUT_MS = 5 * 60 * 1000L;

    private IListener m_listener;
    private String m_step = STEP_PASSWORD;
    private String m_userName;
    private String m_password;
    private String m_currentPassword;
    private String m_newPassword;
    private String m_confirmPassword;
    private String m_notice;

    /** Utente con password provvisoria verificata: deve cambiarla prima di entrare. */
    private AuthService.Session m_pending;
    private long m_pendingSince;
    /** true = cambio volontario dal collegamento (dopo il cambio si torna al logon, non si entra). */
    private boolean m_voluntary;

    @Override public String getPageName() { return "/Logon.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.LogonUI}"; }

    public void prepare(IListener listener) {
        m_listener = listener;
    }

    // ------------------------------------------------------------------ azioni

    public void onLogon(ActionEvent ae) {
        m_notice = null;
        if (blank(m_userName) || blank(m_password)) {
            Statusbar.outputError("Inserire utente e password");
            return;
        }
        try {
            AuthService.Session s = EwfApp.get().auth().login(m_userName.trim(), m_password.toCharArray());
            m_password = null;
            if (s.mustChangePassword()) {
                m_pending = s;
                m_pendingSince = System.currentTimeMillis();
                m_voluntary = false;
                m_step = STEP_CHANGE;
                m_notice = "La password e' provvisoria: sceglierne una nuova per continuare.";
                return;
            }
            enter(s);
        } catch (ServiceException e) {
            m_password = null;
            Statusbar.outputError(e.getMessage());
        } catch (RuntimeException e) {
            m_password = null;
            System.err.println("[LogonUI] Errore di logon: " + e);
            Statusbar.outputError("Errore durante il logon. Riprovare; se il problema persiste contattare l'assistenza.");
        }
    }

    /** Collegamento "Cambia password..." dalla pagina di logon. */
    public void onRequestChange(ActionEvent ae) {
        m_notice = null;
        m_pending = null;
        m_voluntary = true;
        m_step = STEP_CHANGE;
        clearChangeFields();
    }

    public void onChangePassword(ActionEvent ae) {
        try {
            if (blank(m_currentPassword) || blank(m_newPassword)) {
                Statusbar.outputError("Inserire la password attuale e quella nuova");
                return;
            }
            if (!m_newPassword.equals(m_confirmPassword)) {
                Statusbar.outputError("La nuova password e la conferma non coincidono");
                return;
            }
            AuthService auth = EwfApp.get().auth();
            AuthService.Session s;
            if (m_voluntary) {
                if (blank(m_userName)) {
                    Statusbar.outputError("Inserire l'utente");
                    return;
                }
                s = auth.login(m_userName.trim(), m_currentPassword.toCharArray());   // verifica e contatore tentativi
            } else {
                if (m_pending == null || System.currentTimeMillis() - m_pendingSince > CHANGE_TIMEOUT_MS) {
                    backToPassword();
                    Statusbar.outputWarning("Tempo scaduto: ripetere il logon.");
                    return;
                }
                s = m_pending;
            }
            auth.changePassword(s.userId(), m_currentPassword.toCharArray(), m_newPassword.toCharArray());
            clearChangeFields();
            if (m_voluntary) {
                backToPassword();
                m_notice = "Password cambiata: accedere con quella nuova.";
                return;
            }
            AuthService.Session entered = new AuthService.Session(s.userId(), s.login(), s.displayName(), s.sapUser(), false);
            m_pending = null;
            enter(entered);
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("[LogonUI] Errore cambio password: " + e);
            Statusbar.outputError("Errore durante il cambio password. Riprovare.");
        } finally {
            m_currentPassword = null;
        }
    }

    public void onCancelChange(ActionEvent ae) {
        backToPassword();
    }

    private void enter(AuthService.Session s) {
        m_step = STEP_PASSWORD;
        Statusbar.outputSuccess("Benvenuto " + s.displayName());
        if (m_listener != null) m_listener.reactOnLogon(s);
    }

    private void backToPassword() {
        m_step = STEP_PASSWORD;
        m_pending = null;
        m_voluntary = false;
        clearChangeFields();
    }

    private void clearChangeFields() {
        m_currentPassword = null;
        m_newPassword = null;
        m_confirmPassword = null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------ binding

    public boolean isStepPassword() { return STEP_PASSWORD.equals(m_step); }
    public boolean isStepChange() { return STEP_CHANGE.equals(m_step); }
    public boolean isUserEditable() { return m_voluntary; }
    public String getChangeTitle() { return m_voluntary ? "Cambio password" : "Nuova password obbligatoria"; }
    public String getRules() {
        return "Almeno " + AuthService.MIN_LENGTH + " caratteri, con lettere e cifre, senza il nome utente.";
    }

    public String getNotice() { return m_notice; }
    public void setNotice(String v) { m_notice = v; }
    public boolean isNoticeVisible() { return m_notice != null && !m_notice.isBlank(); }

    public String getUserName() { return m_pending != null ? m_pending.login() : m_userName; }
    public void setUserName(String v) { if (m_pending == null) m_userName = v; }
    public String getPassword() { return m_password; }
    public void setPassword(String v) { m_password = v; }
    public String getCurrentPassword() { return m_currentPassword; }
    public void setCurrentPassword(String v) { m_currentPassword = v; }
    public String getNewPassword() { return m_newPassword; }
    public void setNewPassword(String v) { m_newPassword = v; }
    public String getConfirmPassword() { return m_confirmPassword; }
    public void setConfirmPassword(String v) { m_confirmPassword = v; }
}
