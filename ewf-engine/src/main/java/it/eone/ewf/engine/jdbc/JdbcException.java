package it.eone.ewf.engine.jdbc;

import java.sql.SQLException;

/** SQLException non controllata. */
public final class JdbcException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public JdbcException(SQLException cause) { super(cause.getMessage(), cause); }
    public JdbcException(String message) { super(message); }
}
