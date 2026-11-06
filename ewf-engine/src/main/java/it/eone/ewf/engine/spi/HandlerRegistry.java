package it.eone.ewf.engine.spi;

import java.util.Optional;

public interface HandlerRegistry {
    Optional<Handler> find(String handlerCode);
}
