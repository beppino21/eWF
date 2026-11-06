package it.eone.ewf.engine;

import it.eone.ewf.engine.assign.AssignmentRule;
import it.eone.ewf.engine.memory.*;
import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.spi.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class MemoryBackend implements Backend {
    private final InMemoryDefinitionStore defs = new InMemoryDefinitionStore();
    private final InMemoryRuntimeStore store = new InMemoryRuntimeStore();
    private final InMemoryUserDirectory users = new InMemoryUserDirectory();
    private final List<AssignmentRule> rules = new ArrayList<>();
    private final ListAuditSink audit = new ListAuditSink();
    private final LocalTransactions tx = new LocalTransactions();

    @Override public DefinitionStore defs() { return defs; }
    @Override public RuntimeStore store() { return store; }
    @Override public UserDirectory users() { return users; }
    @Override public Supplier<List<AssignmentRule>> rules() { return () -> List.copyOf(rules); }
    @Override public AuditSink audit() { return audit; }
    @Override public Transactions tx() { return tx; }

    @Override public void addUser(long id, String login, String name, String sap, int w) { users.add(id, login, name, sap, w); }
    @Override public void deactivate(long id) { users.deactivate(id); }
    @Override public void processWeight(long u, String t, int w) { users.processWeight(u, t, w); }
    @Override public void substitution(long u, long s, LocalDate f, LocalDate t) { users.substitution(u, s, f, t); }
    @Override public void teamMember(String team, long u) { users.teamMember(team, u); }
    @Override public void addRule(AssignmentRule r) { rules.add(r); }
    @Override public void removeRules(String role) { rules.removeIf(r -> r.roleCode().equals(role)); }
    @Override public void defineType(ProcessType t) { defs.type(t); }
    @Override public long activate(ProcessVersion v) { defs.activate(v); return v.id(); }
    @Override public List<String> auditTypes(long processId) { return audit.types(processId); }
}
