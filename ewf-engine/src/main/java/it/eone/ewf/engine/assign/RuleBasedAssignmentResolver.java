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

    @Override
    public Optional<Assignment> resolve(Query q) {
        return rules.get().stream()
                .filter(r -> r.roleCode().equals(q.roleCode()))
                .filter(r -> matches(r.processType(), q.processType()))
                .filter(r -> matches(r.purchasingGroup(), q.context().get(PURCHASING_GROUP)))
                .filter(r -> matches(r.plant(), q.context().get(PLANT)))
                .filter(r -> matches(r.storageLocation(), q.context().get(STORAGE_LOCATION)))
                .filter(r -> r.validFrom() == null || !q.date().isBefore(r.validFrom()))
                .filter(r -> r.validTo() == null || !q.date().isAfter(r.validTo()))
                .filter(r -> inRange(r, q))
                .sorted(Comparator.comparingInt(AssignmentRule::specificity).reversed()
                        .thenComparingLong(AssignmentRule::id))
                .findFirst()
                .map(r -> new Assignment(r.userId(), r.targetTeam()));
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
