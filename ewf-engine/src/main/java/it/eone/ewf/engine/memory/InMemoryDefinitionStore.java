package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.spi.DefinitionStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryDefinitionStore implements DefinitionStore {
    private final Map<String, ProcessType> types = new HashMap<>();
    private final Map<Long, ProcessVersion> versions = new HashMap<>();
    private final Map<String, Long> active = new HashMap<>();

    public InMemoryDefinitionStore type(ProcessType t) { types.put(t.code(), t); return this; }

    /** Registra una versione e la rende attiva per il suo tipo processo. */
    public InMemoryDefinitionStore activate(ProcessVersion v) {
        versions.put(v.id(), v);
        active.put(v.processTypeCode(), v.id());
        return this;
    }

    @Override public Optional<ProcessType> processType(String code) { return Optional.ofNullable(types.get(code)); }
    @Override public Optional<ProcessVersion> activeVersion(String code) {
        return Optional.ofNullable(active.get(code)).map(versions::get);
    }
    @Override public ProcessVersion version(long id) {
        ProcessVersion v = versions.get(id);
        if (v == null) throw new IllegalArgumentException("Versione " + id + " inesistente");
        return v;
    }
}
