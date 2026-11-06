package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.spi.Handler;
import it.eone.ewf.engine.spi.HandlerRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class MapHandlerRegistry implements HandlerRegistry {
    private final Map<String, Handler> map = new HashMap<>();

    public MapHandlerRegistry register(String code, Handler h) { map.put(code, h); return this; }

    @Override public Optional<Handler> find(String code) { return Optional.ofNullable(map.get(code)); }
}
