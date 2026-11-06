package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.DocumentAdapter;
import it.eone.ewf.engine.spi.DocumentAdapterRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Adapter documento in memoria: valori e attributi impostabili a mano (per test e demo). */
public final class MapDocumentAdapters implements DocumentAdapterRegistry {

    public static final class Fixed implements DocumentAdapter {
        private final Map<String, Money> values = new HashMap<>();
        private final Map<String, Map<String, String>> contexts = new HashMap<>();
        public Fixed value(String key, Money v) { values.put(key, v); return this; }
        public Fixed context(String key, Map<String, String> c) { contexts.put(key, Map.copyOf(c)); return this; }
        @Override public Optional<Money> value(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public Map<String, String> context(String key) { return contexts.getOrDefault(key, Map.of()); }
    }

    private final Map<String, DocumentAdapter> map = new HashMap<>();

    public MapDocumentAdapters register(String docType, DocumentAdapter a) { map.put(docType, a); return this; }

    @Override public Optional<DocumentAdapter> find(String docType) { return Optional.ofNullable(map.get(docType)); }
}
