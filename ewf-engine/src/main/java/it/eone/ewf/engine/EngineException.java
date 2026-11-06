package it.eone.ewf.engine;

/** Errore del motore con un codice stabile, utilizzabile dalla UI. */
public final class EngineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        NOT_FOUND, NOT_AUTHORIZED, WEIGHT_TOO_LOW, INVALID_STATE, CONCURRENT_UPDATE,
        DUPLICATE_PROCESS, NO_ACTIVE_VERSION, PROCESS_TYPE_SUSPENDED, INVALID_DEFINITION, BAD_REQUEST
    }

    private final Code code;

    public EngineException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }

    static EngineException of(Code code, String fmt, Object... args) {
        return new EngineException(code, String.format(fmt, args));
    }
}
