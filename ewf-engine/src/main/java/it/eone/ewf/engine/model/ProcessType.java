package it.eone.ewf.engine.model;

import java.math.BigDecimal;

/**
 * Tipo processo (ex /EWF/PROCESSO).
 * changeTolerancePct: per i documenti senza strategia di rilascio (evento CHANGED), variazione di valore
 * o quantita' oltre la quale il processo aperto riparte. 0 = qualsiasi variazione (prassi del vecchio eWF).
 */
public record ProcessType(String code, String description, String docType,
                          boolean suspended, boolean autoOkFirst, boolean noFirstStep,
                          BigDecimal changeTolerancePct) {
    public ProcessType {
        if (changeTolerancePct == null || changeTolerancePct.signum() < 0) changeTolerancePct = BigDecimal.ZERO;
    }

    public ProcessType(String code, String description, String docType,
                       boolean suspended, boolean autoOkFirst, boolean noFirstStep) {
        this(code, description, docType, suspended, autoOkFirst, noFirstStep, BigDecimal.ZERO);
    }
}
