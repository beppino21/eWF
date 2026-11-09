package it.eone.ewf.engine.model;

import java.util.Map;

/**
 * Opzione di un nodo di scelta. Scegliere un'opzione registra il codice tra le scelte del processo
 * (che decidono quali varianti di step si applicano) e prosegue:
 *  - reject = false: come un'approvazione, verso next OK dello step;
 *  - reject = true : come un rifiuto, verso next KO (NULL = fine negativa del processo).
 * handlerCode facoltativo: eseguito alla scelta, prima degli agganci ON_OK/ON_KO del task.
 * Il codice e' univoco nella versione: le varianti di step lo usano per riferirsi al ramo.
 */
public record OptionDef(String code, String label, boolean reject, String handlerCode, Map<String, String> config) {
    public OptionDef {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Codice opzione obbligatorio");
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public static OptionDef of(String code, String label) { return new OptionDef(code, label, false, null, null); }
    public static OptionDef reject(String code, String label) { return new OptionDef(code, label, true, null, null); }
}
