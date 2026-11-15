package it.eone.ewf.server.context;

/**
 * Secondo livello di difesa, come nel ticketing: ogni pagina protetta lo chiama nel costruttore. Il primo
 * livello e' system.xml (si avvia solo /Outest.xml). Non va usato in OutestUI e LogonUI.
 */
public final class SessionGuard {
    private SessionGuard() {}

    public static void requireUser(String page) {
        boolean ok;
        try {
            ok = EwfSession.instance().loggedIn();
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            System.err.println("[SessionGuard] Accesso negato a " + page + ": nessun utente collegato");
            throw new SecurityException("Accesso non autorizzato");
        }
    }
}
