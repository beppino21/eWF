package it.eone.ewf.service.spi;

import it.eone.ewf.service.sheet.DocumentSheet;

import java.util.List;

/**
 * Schede del documento costruite dal sistema di origine (sostituisce le vecchie "visualizza modulo" e
 * "visualizza documento"). Per tipo documento ci possono essere piu' viste, es. per l'OdA "Scheda OdA" e in
 * futuro "Confronto prezzi". La parte eWF (percorso, approvazioni, commenti) la aggiunge il servizio.
 * Implementazioni previste: BAPI via RFC (R/3, S/4HANA on-premise e Private), OData (Public Edition).
 */
public interface DocumentSheets {

    /** Vista disponibile: codice stabile e etichetta del pulsante. */
    record View(String code, String label) {}

    /** Viste per questo documento; vuoto = nessuna. Non deve chiamare il sistema di origine. */
    List<View> views(DocRef doc);

    /**
     * Costruisce la scheda; eccezione non controllata se il sistema di origine non risponde.
     * context: attributi del documento salvati da eWF all'avvio (es. sap.LIFNR_TXT): descrizioni gia' pronte,
     * che evitano letture in piu' al sistema di origine. Puo' essere vuoto.
     */
    DocumentSheet build(DocRef doc, String viewCode, java.util.Map<String, String> context);
}
