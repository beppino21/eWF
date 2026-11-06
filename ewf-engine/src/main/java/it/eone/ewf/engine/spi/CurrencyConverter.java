package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.Money;

import java.time.LocalDate;

public interface CurrencyConverter {
    Money convert(Money value, String toCurrency, LocalDate date);
}
