package it.eone.ewf.engine;

import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.spi.*;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/** Dove girano i test: in memoria o su PostgreSQL. Stessi scenari, stessi risultati attesi. */
interface Backend {
    DefinitionStore defs();
    RuntimeStore store();
    UserDirectory users();
    Supplier<List<AssignmentRule>> rules();
    AuditSink audit();
    Transactions tx();

    void addUser(long id, String login, String name, String sapUser, int weight);
    void deactivate(long userId);
    void processWeight(long userId, String processType, int weight);
    void substitution(long userId, long substituteId, LocalDate from, LocalDate to);
    void teamMember(String team, long userId);
    void addRule(AssignmentRule rule);
    void removeRules(String roleCode);
    void defineType(ProcessType type);
    void addStartRule(it.eone.ewf.engine.model.StartRule rule);

    /** Registra e attiva la versione; restituisce l'id con cui il motore la conosce. */
    long activate(ProcessVersion version);

    List<String> auditTypes(long processId);
}
