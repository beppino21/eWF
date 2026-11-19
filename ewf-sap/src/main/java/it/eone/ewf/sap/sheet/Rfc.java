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

    /** Come {@link #call(String, Map)}, con tabelle passate in ingresso (righe: campo -> valore). */
    default Result call(String function, Map<String, String> imports, Map<String, List<Map<String, String>>> tables) {
        if (tables == null || tables.isEmpty()) return call(function, imports);
        throw new UnsupportedOperationException("Tabelle in ingresso non gestite");
    }

    interface Result {
        Map<String, String> structure(String exportName);
        List<Map<String, String>> table(String tableName);

        /** Parametro di export semplice (CHAR, NUMC...) come testo; "" se assente. */
        default String value(String exportName) { return ""; }

        /** Parametro di export binario (XSTRING, RAW); vuoto se assente. */
        default byte[] bytes(String exportName) { return new byte[0]; }
    }

    final class FunctionMissing extends RuntimeException {
        public FunctionMissing(String function) {
            super("Funzione " + function + " non presente nel sistema");
        }
    }
}
