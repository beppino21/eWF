package it.eone.ewf.sap;

import it.eone.ewf.engine.spi.Handler;
import it.eone.ewf.engine.spi.HandlerRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Restituisce un JcoDispatcherHandler per i codici handler che sono FM ABAP
 * (di default: che iniziano con /EWF/ oppure Z).
 */
public final class SapHandlerRegistry implements HandlerRegistry {

    private final String destinationName;
    private final Predicate<String> isAbapHandler;
    private final Map<String, Handler> cache = new ConcurrentHashMap<>();

    public SapHandlerRegistry(String destinationName) {
        this(destinationName, code -> code.startsWith("/EWF/") || code.startsWith("Z"));
    }

    public SapHandlerRegistry(String destinationName, Predicate<String> isAbapHandler) {
        this.destinationName = destinationName;
        this.isAbapHandler = isAbapHandler;
    }

    @Override
    public Optional<Handler> find(String code) {
        if (code == null || !isAbapHandler.test(code)) return Optional.empty();
        return Optional.of(cache.computeIfAbsent(code, c -> new JcoDispatcherHandler(destinationName, c)));
    }

    /** Prova i registri in ordine: es. handler Java del prodotto prima, poi SAP. */
    public static HandlerRegistry chain(List<HandlerRegistry> registries) {
        return code -> registries.stream().map(r -> r.find(code)).flatMap(Optional::stream).findFirst();
    }
}
