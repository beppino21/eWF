package it.eone.ewf.engine.model;

/** Come un'opzione avvia un processo collegato sullo stesso documento. */
public enum SpawnMode {
    /** Il processo corrente si chiude (FORWARDED, senza azione di fine) e il lavoro passa al nuovo. */
    HANDOFF,
    /** Il processo corrente prosegue per la sua strada; il nuovo vive in parallelo. */
    PARALLEL
}
