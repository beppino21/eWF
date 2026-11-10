package it.eone.ewf.engine.model;

import java.util.Map;

/**
 * Regola di avvio: quale processo far partire quando arriva un documento nuovo (evento CREATED o RESET
 * senza processo aperto). Le condizioni sono attributi del documento letti da DOC_INFO
 * (es. sap.BSART = ZCL, company_code = S100); "*" = qualsiasi valore. Vince la regola applicabile
 * con priorita' piu' alta; a parita', quella con piu' condizioni (la piu' specifica).
 */
public record StartRule(long id, String docType, Map<String, String> conditions, int priority, String processType) {
    public StartRule {
        conditions = conditions == null ? Map.of() : Map.copyOf(conditions);
    }

    public boolean matches(Map<String, String> context) {
        for (var c : conditions.entrySet()) {
            if ("*".equals(c.getValue())) continue;
            if (!c.getValue().equals(context.get(c.getKey()))) return false;
        }
        return true;
    }
}
