package it.eone.ewf.service;

import it.eone.ewf.engine.EngineException;

/** Errore del livello di servizio: il messaggio e' gia' pensato per l'utente. */
public final class ServiceException extends RuntimeException {

    public enum Code {
        AUTH_FAILED,        // credenziali errate o utente bloccato (messaggio volutamente generico)
        NOT_FOUND,
        NOT_AUTHORIZED,
        INVALID,            // dati non validi (testo vuoto, password debole, file troppo grande, ...)
        INVALID_STATE,      // processo o task non piu' nello stato atteso (es. gia' firmato da un collega)
        NOT_SUPPORTED,      // funzione non disponibile per questo tipo documento o sistema
        BACKEND,            // sistema di origine non raggiungibile o in errore
        FUNCTION_FAILED     // funzione speciale terminata con esito negativo
    }

    private final Code code;

    public ServiceException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ServiceException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() { return code; }

    public static ServiceException of(Code code, String fmt, Object... args) {
        return new ServiceException(code, String.format(fmt, args));
    }

    /** Traduce un errore del motore mantenendone il messaggio. */
    public static ServiceException from(EngineException e) {
        Code c = switch (e.code()) {
            case NOT_FOUND, DOCUMENT_NOT_FOUND -> Code.NOT_FOUND;
            case NOT_AUTHORIZED, WEIGHT_TOO_LOW -> Code.NOT_AUTHORIZED;
            case BACKEND_UNAVAILABLE -> Code.BACKEND;
            case INVALID_STATE, CONCURRENT_UPDATE -> Code.INVALID_STATE;
            default -> Code.INVALID;
        };
        return new ServiceException(c, e.getMessage(), e);
    }
}
