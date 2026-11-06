package it.eone.ewf.engine.jdbc;

import it.eone.ewf.engine.EngineConfig;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.assign.RuleBasedAssignmentResolver;
import it.eone.ewf.engine.spi.CurrencyConverter;
import it.eone.ewf.engine.spi.DocumentAdapterRegistry;
import it.eone.ewf.engine.spi.HandlerRegistry;

import javax.sql.DataSource;
import java.time.Clock;

/** Monta il motore su PostgreSQL. Handler, adapter e cambi restano da fornire. */
public final class JdbcEngineFactory {
    private JdbcEngineFactory() {}

    public static WorkflowEngine create(DataSource ds, HandlerRegistry handlers, DocumentAdapterRegistry adapters,
                                        CurrencyConverter currency, Clock clock, EngineConfig config) {
        JdbcTransactions db = new JdbcTransactions(ds);
        return new WorkflowEngine(
                new JdbcDefinitionStore(db),
                new JdbcRuntimeStore(db),
                new JdbcUserDirectory(db),
                new RuleBasedAssignmentResolver(new JdbcAssignmentRules(db), currency),
                handlers, adapters, currency,
                new JdbcAuditSink(db),
                db, clock, config);
    }
}
