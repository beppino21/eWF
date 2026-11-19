package it.eone.ewf.service.spi;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Allegati del documento nel sistema di origine (attachment_scope = DOCUMENT).
 * Implementazioni previste: GOS via RFC (ECC, S/4HANA on-premise e Private), API_CV_ATTACHMENT_SRV via OData
 * (S/4HANA Public Edition), documentale esterno. Il servizio non sa da dove arrivano.
 * Le eccezioni non controllate sono mostrate all'utente come "sistema di origine non disponibile".
 */
public interface DocumentAttachments {

    /** Allegato come lo vede il sistema di origine. id: opaco, lo stesso che si ripassa a {@link #read}. */
    record Item(String id, String fileName, String mimeType, Long sizeBytes, String createdBy, Instant createdAt) {}

    boolean supports(DocRef doc);

    List<Item> list(DocRef doc);

    byte[] read(DocRef doc, String id);

    /**
     * Numero di allegati per piu' documenti in una sola chiamata (per i pulsanti della lista). Chiave della mappa:
     * il DocRef ricevuto. Un documento assente dalla mappa = numero non noto. Default: nessun conteggio.
     */
    default Map<DocRef, Integer> counts(Collection<DocRef> docs) { return Map.of(); }

    /** Se il sistema di origine accetta nuovi allegati da eWF per questo documento. */
    default boolean canAdd(DocRef doc) { return true; }

    /**
     * Aggiunge un allegato. sapUser: utente SAP di chi carica, se noto (per la traccia nel sistema di origine);
     * uploaderName: nome leggibile, da usare quando l'utente SAP non c'e'. Restituisce l'id del nuovo allegato.
     */
    String add(DocRef doc, String fileName, String mimeType, byte[] content, String sapUser, String uploaderName);
}
