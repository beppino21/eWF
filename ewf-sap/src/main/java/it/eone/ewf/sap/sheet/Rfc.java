package it.eone.ewf.sap.sheet;

import java.util.List;
import java.util.Map;

/**
 * Chiamata RFC in lettura, con risultati "neutri" (testi): separa la chiamata vera (JCo) da chi interpreta i
 * dati, che cosi' si prova con dati finti. Un campo assente nella struttura di quel rilascio SAP vale "".
 */
public interface Rfc {

    /** Esegue la funzione. FunctionMissing se non esiste nel sistema; RuntimeException se SAP non risponde. */
    Result call(String function, Map<String, String> imports);

    interface Result {
        Map<String, String> structure(String exportName);
        List<Map<String, String>> table(String tableName);
    }

    final class FunctionMissing extends RuntimeException {
        public FunctionMissing(String function) {
            super("Funzione " + function + " non presente nel sistema");
        }
    }
}
