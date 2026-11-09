package it.eone.ewf.engine.model;

/** Collegamento tra processi (ex LINKPROCESS). RESTART = riavvio con nuova definizione. */
public record ProcessLink(long parentId, long childId, String linkType) {
    public static final String RESTART = "RS";
    /** Avviato da un'opzione: il padre si e' chiuso passando il lavoro al figlio. */
    public static final String HANDOFF = "HO";
    /** Avviato da un'opzione: padre e figlio proseguono in parallelo. */
    public static final String PARALLEL = "PA";
}
