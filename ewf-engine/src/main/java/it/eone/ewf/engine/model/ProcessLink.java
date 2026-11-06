package it.eone.ewf.engine.model;

/** Collegamento tra processi (ex LINKPROCESS). RESTART = riavvio con nuova definizione. */
public record ProcessLink(long parentId, long childId, String linkType) {
    public static final String RESTART = "RS";
}
