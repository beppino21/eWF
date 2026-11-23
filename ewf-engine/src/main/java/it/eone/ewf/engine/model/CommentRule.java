package it.eone.ewf.engine.model;

/**
 * Commento richiesto quando si esegue un'azione sul task (approvazione o rifiuto).
 * NONE: il campo non viene proposto; OPTIONAL: proposto, si puo' lasciare vuoto;
 * REQUIRED: senza commento il motore rifiuta l'azione (vale per tutti i canali: web, mobile, API).
 * Predefiniti: approvazione OPTIONAL, rifiuto REQUIRED.
 */
public enum CommentRule {
    NONE, OPTIONAL, REQUIRED;

    public static final CommentRule DEFAULT_APPROVE = OPTIONAL;
    public static final CommentRule DEFAULT_REJECT = REQUIRED;
}
