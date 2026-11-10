package it.eone.connect.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Accesso al DB dell'applicazione: ogni tx() usa una connessione propria e la chiude. */
public final class Db {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private final DataSource ds;

    public Db(DataSource ds) {
        this.ds = ds;
    }

    public DataSource dataSource() { return ds; }

    /** Esegue in una transazione: commit se va, rollback se lancia. */
    public <T> T tx(SqlWork<T> work) {
        try (Connection c = ds.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    public static final class DbException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DbException(String message, Throwable cause) { super(message, cause); }
    }
}
