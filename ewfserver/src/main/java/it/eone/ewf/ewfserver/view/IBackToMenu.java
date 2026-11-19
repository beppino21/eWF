package it.eone.ewf.ewfserver.view;

import java.io.Serializable;

/** Pulsante "« Menu" delle pagine aperte dal menu: riporta alla pagina iniziale. */
public interface IBackToMenu extends Serializable {
    void backToMenu();
}
