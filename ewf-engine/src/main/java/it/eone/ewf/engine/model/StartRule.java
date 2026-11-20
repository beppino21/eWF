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
        return mismatch(context) == null;
    }

    /** null se la regola si applica; altrimenti la prima condizione non soddisfatta, in chiaro. */
    public String mismatch(Map<String, String> context) {
        for (var c : conditions.entrySet()) {
            if ("*".equals(c.getValue())) continue;
            String v = context.get(c.getKey());
            if (!c.getValue().equals(v))
                return c.getKey() + " = " + c.getValue() + " (documento: " + (v == null ? "assente" : v) + ")";
        }
        return null;
    }

    /** Ordine di preferenza: priorita' piu' alta, poi piu' condizioni, poi la regola piu' vecchia. */
    public static final java.util.Comparator<StartRule> PREFERENCE = java.util.Comparator
            .comparingInt(StartRule::priority).reversed()
            .thenComparing(java.util.Comparator.comparingInt((StartRule r) -> r.conditions().size()).reversed())
            .thenComparingLong(StartRule::id);

    /** La regola che il motore applica al documento: la prima applicabile nell'ordine di preferenza. */
    public static java.util.Optional<StartRule> choose(java.util.Collection<StartRule> rules, Map<String, String> context) {
        return rules.stream().filter(r -> r.matches(context)).sorted(PREFERENCE).findFirst();
    }
}
