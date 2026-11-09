package it.eone.ewf.engine.model;

import java.util.Map;

/**
 * Opzione di un nodo di scelta. Scegliere un'opzione registra il codice tra le scelte del processo
 * (che decidono quali varianti di step si applicano) e prosegue:
 *  - reject = false: come un'approvazione, verso next OK dello step;
 *  - reject = true : come un rifiuto, verso next KO (NULL = fine negativa del processo).
 * handlerCode facoltativo: eseguito alla scelta, prima degli agganci ON_OK/ON_KO del task.
 * startProcess facoltativo: tipo processo da avviare sullo stesso documento, in modalita' spawnMode
 *  (HANDOFF: il processo corrente si chiude come FORWARDED; PARALLEL: prosegue e il nuovo vive accanto).
 * Il codice e' univoco nella versione: le varianti di step lo usano per riferirsi al ramo.
 */
public record OptionDef(String code, String label, boolean reject, String handlerCode, Map<String, String> config,
                        String startProcess, SpawnMode spawnMode) {
    public OptionDef {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Codice opzione obbligatorio");
        config = config == null ? Map.of() : Map.copyOf(config);
        if (startProcess != null && startProcess.isBlank()) startProcess = null;
        if (startProcess == null) spawnMode = null;
        else if (spawnMode == null) spawnMode = SpawnMode.HANDOFF;
    }

    public OptionDef(String code, String label, boolean reject, String handlerCode, Map<String, String> config) {
        this(code, label, reject, handlerCode, config, null, null);
    }

    public static OptionDef of(String code, String label) { return new OptionDef(code, label, false, null, null); }
    public static OptionDef reject(String code, String label) { return new OptionDef(code, label, true, null, null); }

    /** Opzione che passa il lavoro a un altro processo (es. dal processo di "Avvio" all'approvazione vera). */
    public static OptionDef handoff(String code, String label, String processType) {
        return new OptionDef(code, label, false, null, null, processType, SpawnMode.HANDOFF);
    }

    public static OptionDef parallel(String code, String label, String processType) {
        return new OptionDef(code, label, false, null, null, processType, SpawnMode.PARALLEL);
    }

    public boolean isHandoff() { return spawnMode == SpawnMode.HANDOFF; }
}
