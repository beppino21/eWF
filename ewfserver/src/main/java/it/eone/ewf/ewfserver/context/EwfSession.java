package it.eone.ewf.ewfserver.context;

import it.eone.ewf.service.auth.AuthService;
import org.eclnt.jsfserver.util.HttpSessionAccess;

import java.io.Serializable;

/** Utente collegato, nella sessione di dialogo CaptainCasa (una per scheda del browser). */
public final class EwfSession implements Serializable {
    private static final long serialVersionUID = 1L;

    private AuthService.Session user;

    public static EwfSession instance() {
        var ds = HttpSessionAccess.getCurrentDialogSession();
        EwfSession s = (EwfSession) ds.getAttribute(EwfSession.class.getName());
        if (s == null) {
            s = new EwfSession();
            ds.setAttribute(EwfSession.class.getName(), s);
        }
        return s;
    }

    public AuthService.Session user() { return user; }
    public void login(AuthService.Session s) { user = s; }
    public void logout() { user = null; }
    public boolean loggedIn() { return user != null; }

    /** Id dell'utente collegato; eccezione se nessuno (le pagine lo chiamano dopo SessionGuard). */
    public long userId() {
        if (user == null) throw new SecurityException("Nessun utente collegato");
        return user.userId();
    }
}
