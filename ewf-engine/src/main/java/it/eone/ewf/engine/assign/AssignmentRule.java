package it.eone.ewf.engine.assign;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Regola di assegnazione (ex /EWF/USER_TASK). null = "qualsiasi valore".
 * Destinatario: un utente, un team, o entrambi.
 */
public record AssignmentRule(
        long id,
        String processType,
        String roleCode,
        String purchasingGroup,
        String plant,
        String storageLocation,
        BigDecimal amountFrom,
        BigDecimal amountTo,
        String currency,
        Long userId,
        String targetTeam,
        LocalDate validFrom,
        LocalDate validTo) {

    /** Numero di criteri valorizzati: piu' e' alto, piu' la regola e' specifica. */
    public int specificity() {
        int n = 0;
        if (processType != null) n++;
        if (purchasingGroup != null) n++;
        if (plant != null) n++;
        if (storageLocation != null) n++;
        return n;
    }
}
