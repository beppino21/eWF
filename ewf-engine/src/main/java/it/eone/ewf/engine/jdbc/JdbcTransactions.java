package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.spi.Transactions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Transazioni JDBC. La connessione della transazione e' legata al thread:
 * tutti gli store Jdbc* la usano automaticamente. Fuori da una transazione
 * gli store aprono una connessione breve in autocommit (solo letture o
 * scritture singole).
 * Una transazione dentro un'altra si unisce a quella esterna.
 */
public final class JdbcTransactions implements Transactions {

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection c) throws SQLException;
    }

    private final DataSource dataSource;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public JdbcTransactions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (current.get() != null) return work.get();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            current.set(c);
            try {
                T result = work.get();
                c.commit();
                return result;
            } catch (RuntimeException | Error e) {
                c.rollback();
                throw e;
            } finally {
                current.remove();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new JdbcException(e);
        }
    }

    /** Esegue sulla connessione della transazione corrente, o su una nuova in autocommit. */
    public <T> T with(SqlWork<T> work) {
        Connection c = current.get();
        try {
            if (c != null) return work.apply(c);
            try (Connection n = dataSource.getConnection()) {
                return work.apply(n);
            }
        } catch (SQLException e) {
            throw new JdbcException(e);
        }
    }

    public boolean inTransaction() { return current.get() != null; }
}
