package it.eone.ewf.notify;

/** Destinatario di una mail: utente eWF con indirizzo. onBehalfOf = titolare, se e' un sostituto. */
record Recipient(long id, String login, String name, String email, String onBehalfOf) {}
