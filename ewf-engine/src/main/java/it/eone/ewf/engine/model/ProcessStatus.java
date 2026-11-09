package it.eone.ewf.engine.model;

public enum ProcessStatus {
    RUNNING, SUSPENDED, COMPLETING, COMPLETED, REJECTED, CANCELLED, ERROR,
    /** Chiuso passando il lavoro a un altro processo (opzione HANDOFF): non e' un'approvazione. */
    FORWARDED;

    public boolean isOpen() {
        return this == RUNNING || this == SUSPENDED || this == COMPLETING || this == ERROR;
    }
}
