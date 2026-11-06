package it.eone.ewf.engine.model;

public enum ProcessStatus {
    RUNNING, SUSPENDED, COMPLETING, COMPLETED, REJECTED, CANCELLED, ERROR;

    public boolean isOpen() {
        return this == RUNNING || this == SUSPENDED || this == COMPLETING || this == ERROR;
    }
}
