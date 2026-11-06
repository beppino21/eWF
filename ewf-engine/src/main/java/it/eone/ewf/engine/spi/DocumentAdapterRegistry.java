package it.eone.ewf.engine.spi;

import java.util.Optional;

public interface DocumentAdapterRegistry {
    Optional<DocumentAdapter> find(String docType);
}
