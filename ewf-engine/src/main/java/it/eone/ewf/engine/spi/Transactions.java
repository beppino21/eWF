package it.eone.ewf.engine.spi;

import java.util.function.Supplier;

/** Confine transazionale. Gli handler NON vengono mai chiamati dentro una transazione. */
public interface Transactions {
    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> { work.run(); return null; });
    }
}
