package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.Money;

import java.util.Map;

/**
 * Uno per tipo documento (ex WF_DOCVALUE + WF_BUKRS_WERKS + letture EKKO/EBAN di WF_USER_TASK).
 * Solo lettura: non modifica nulla nel sistema di origine.
 *
 * Il motore lo chiama all'avvio del processo e salva sul processo valore e contesto:
 * soglie e regole usano quella fotografia. Un documento modificato in modo rilevante
 * si gestisce con il riavvio (annulla + nuovo processo, che rilegge il documento).
 */
public interface DocumentAdapter {

    /**
     * @throws UnavailableException se il sistema di origine non risponde: l'avvio viene rifiutato
     */
    DocumentInfo read(String documentKey);

    /**
     * exists  = il documento esiste e non e' cancellato
     * value   = valore per soglie e fasce (gia' nella divisa di riferimento), null se non significativo
     * context = attributi per le regole: purchasing_group, plant, storage_location, company_code, ...
     * message = testo per l'utente (es. motivo per cui non esiste)
     */
    record DocumentInfo(boolean exists, Money value, Map<String, String> context, String message) {
        public DocumentInfo {
            context = context == null ? Map.of() : Map.copyOf(context);
        }

        public static DocumentInfo found(Money value, Map<String, String> context) {
            return new DocumentInfo(true, value, context, null);
        }

        public static DocumentInfo notFound(String message) {
            return new DocumentInfo(false, null, Map.of(), message);
        }
    }

    final class UnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
