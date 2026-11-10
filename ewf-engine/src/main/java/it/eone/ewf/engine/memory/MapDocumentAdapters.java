package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.DocumentAdapter;
import it.eone.ewf.engine.spi.DocumentAdapterRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Adapter documento in memoria: valori e attributi impostabili a mano (per test e demo). */
public final class MapDocumentAdapters implements DocumentAdapterRegistry {

    /** Ogni documento esiste, salvo quelli dichiarati mancanti; offline simula il sistema irraggiungibile. */
    public static final class Fixed implements DocumentAdapter {
        private final Map<String, Money> values = new HashMap<>();
        private final Map<String, Map<String, String>> contexts = new HashMap<>();
        private final Map<String, java.util.List<it.eone.ewf.engine.model.DocItem>> items = new HashMap<>();
        private final Set<String> missing = new HashSet<>();
        private boolean offline;
        private int reads;

        public Fixed value(String key, Money v) { values.put(key, v); return this; }
        public Fixed context(String key, Map<String, String> c) { contexts.put(key, Map.copyOf(c)); return this; }
        public Fixed missing(String key) { missing.add(key); return this; }
        public Fixed items(String key, java.util.List<it.eone.ewf.engine.model.DocItem> l) {
            items.put(key, java.util.List.copyOf(l));
            return this;
        }
        public Fixed offline(boolean b) { offline = b; return this; }
        public int reads() { return reads; }

        @Override
        public DocumentInfo read(String key) {
            reads++;
            if (offline) throw new UnavailableException("sistema non raggiungibile (simulato)", null);
            if (missing.contains(key)) return DocumentInfo.notFound("cancellato");
            return DocumentInfo.found(values.get(key), contexts.getOrDefault(key, Map.of()),
                    items.getOrDefault(key, java.util.List.of()));
        }
    }

    private final Map<String, DocumentAdapter> map = new HashMap<>();

    public MapDocumentAdapters register(String docType, DocumentAdapter a) { map.put(docType, a); return this; }

    @Override public Optional<DocumentAdapter> find(String docType) { return Optional.ofNullable(map.get(docType)); }
}
