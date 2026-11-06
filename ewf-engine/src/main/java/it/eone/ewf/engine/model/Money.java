package it.eone.ewf.engine.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Importo con divisa. */
public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }
}
