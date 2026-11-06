package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.spi.Transactions;

import java.util.function.Supplier;

/** Serializza le "transazioni" in memoria. Non fa rollback: va bene solo per test e demo. */
public final class LocalTransactions implements Transactions {
    @Override public synchronized <T> T inTransaction(Supplier<T> work) { return work.get(); }
}
