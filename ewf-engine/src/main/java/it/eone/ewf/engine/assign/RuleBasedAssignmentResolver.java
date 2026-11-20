package it.eone.ewf.engine.assign;

import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.AssignmentResolver;
import it.eone.ewf.engine.spi.CurrencyConverter;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Generalizza la cascata di /EWF/WF_USER_TASK: invece di cinque SELECT a specificita'
 * decrescente, tra le regole compatibili vince quella con piu' criteri valorizzati;
 * a parita', la regola con id minore (deterministico).
 *
 * Compatibile = ruolo uguale; ogni criterio valorizzato uguale all'attributo del documento;
 * valore nella fascia (convertito nella divisa della regola); data nella validita'.
 *
 * Il controllo di budget di WF_USER_TASK (ordinato annuo per fornitore, S011) non e' qui:
 * andra' come filtro aggiuntivo fornito dall'adapter del tipo documento.
 */
public final class RuleBasedAssignmentResolver implements AssignmentResolver {

    public static final String PURCHASING_GROUP = "purchasing_group";
    public static final String PLANT = "plant";
    public static final String STORAGE_LOCATION = "storage_location";

    private final Supplier<List<AssignmentRule>> rules;
    private final CurrencyConverter currency;

    public RuleBasedAssignmentResolver(Supplier<List<AssignmentRule>> rules, CurrencyConverter currency) {
        this.rules = rules;
        this.currency = currency;
    }

    /** Una regola del ruolo valutata per una richiesta: excludedBecause null = compatibile. */
    public record Evaluation(AssignmentRule rule, String excludedBecause) {
        public boolean compatible() { return excludedBecause == null; }
    }

    @Override
    public Optional<Assignment> resolve(Query q) {
        return evaluate(q, false).stream().filter(Evaluation::compatible).findFirst()
                .map(e -> new Assignment(e.rule().userId(), e.rule().targetTeam()));
    }

    /**
     * Tutte le regole del ruolo, con il motivo per cui sono escluse. Prima le compatibili, nell'ordine in cui
     * il motore le sceglie (la prima vince), poi le escluse. E' lo stesso codice di {@link #resolve}: il
     * simulatore del designer mostra esattamente cio' che farebbe il motore.
     */
    public List<Evaluation> explain(Query q) {
        return evaluate(q, true);
    }

    /** lenient: un cambio mancante esclude la regola con il motivo (simulatore) invece di fallire (motore). */
    private List<Evaluation> evaluate(Query q, boolean lenient) {
        List<Evaluation> out = new java.util.ArrayList<>();
        for (AssignmentRule r : rules.get()) {
            if (!r.roleCode().equals(q.roleCode())) continue;
            out.add(new Evaluation(r, why(r, q, lenient)));
        }
        out.sort(Comparator.comparing((Evaluation e) -> !e.compatible())
                .thenComparing(Comparator.comparingInt((Evaluation e) -> e.rule().specificity()).reversed())
                .thenComparingLong(e -> e.rule().id()));
        return out;
    }

    private String why(AssignmentRule r, Query q, boolean lenient) {
        if (!matches(r.processType(), q.processType())) return "altro tipo processo (" + r.processType() + ")";
        if (!matches(r.purchasingGroup(), q.context().get(PURCHASING_GROUP)))
            return "gruppo acquisti " + r.purchasingGroup();
        if (!matches(r.plant(), q.context().get(PLANT))) return "divisione " + r.plant();
        if (!matches(r.storageLocation(), q.context().get(STORAGE_LOCATION))) return "magazzino " + r.storageLocation();
        if (r.validFrom() != null && q.date().isBefore(r.validFrom())) return "valida dal " + r.validFrom();
        if (r.validTo() != null && q.date().isAfter(r.validTo())) return "scaduta il " + r.validTo();
        try {
            if (!inRange(r, q)) return q.value() == null ? "ha una fascia di importo e il documento non ha valore"
                    : "importo fuori dalla fascia";
        } catch (RuntimeException e) {
            if (!lenient) throw e;
            return "importo non confrontabile: " + e.getMessage();
        }
        return null;
    }

    private static boolean matches(String ruleValue, String docValue) {
        return ruleValue == null || Objects.equals(ruleValue, docValue);
    }

    private boolean inRange(AssignmentRule r, Query q) {
        if (r.amountFrom() == null && r.amountTo() == null) return true;
        if (q.value() == null) return false;
        Money v = r.currency() == null ? q.value() : currency.convert(q.value(), r.currency(), q.date());
        if (r.amountFrom() != null && v.amount().compareTo(r.amountFrom()) < 0) return false;
        return r.amountTo() == null || v.amount().compareTo(r.amountTo()) <= 0;
    }

    /** Comodita' per i test. */
    public static Map<String, String> ctx(String purchasingGroup, String plant, String storageLocation) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>();
        if (purchasingGroup != null) m.put(PURCHASING_GROUP, purchasingGroup);
        if (plant != null) m.put(PLANT, plant);
        if (storageLocation != null) m.put(STORAGE_LOCATION, storageLocation);
        return m;
    }
}
