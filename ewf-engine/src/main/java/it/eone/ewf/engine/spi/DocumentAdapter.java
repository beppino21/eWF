package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.Money;

import java.util.Map;
import java.util.Optional;

/** Uno per tipo documento (ex WF_DOCVALUE / WF_BUKRS_WERKS / EBAN-EKKO in WF_USER_TASK). */
public interface DocumentAdapter {
    Optional<Money> value(String documentKey);

    /** Attributi per il risolutore regole: purchasing_group, plant, storage_location, ... */
    Map<String, String> context(String documentKey);
}
