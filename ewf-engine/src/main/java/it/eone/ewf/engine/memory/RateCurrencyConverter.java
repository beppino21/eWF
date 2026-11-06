package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.CurrencyConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Cambi fissi "DA->A". In produzione: tassi da SAP (TCURR) o da servizio esterno. */
public final class RateCurrencyConverter implements CurrencyConverter {
    private final Map<String, BigDecimal> rates = new HashMap<>();

    public RateCurrencyConverter rate(String from, String to, String rate) {
        rates.put(from + "->" + to, new BigDecimal(rate));
        return this;
    }

    @Override public Money convert(Money value, String to, LocalDate date) {
        if (value.currency().equals(to)) return value;
        BigDecimal r = rates.get(value.currency() + "->" + to);
        if (r == null) throw new IllegalStateException("Cambio mancante " + value.currency() + "->" + to);
        return new Money(value.amount().multiply(r).setScale(2, RoundingMode.HALF_UP), to);
    }
}
