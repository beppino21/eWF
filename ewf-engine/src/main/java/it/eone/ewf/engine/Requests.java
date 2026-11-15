package it.eone.ewf.engine;

import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.ProcessStatus;
import it.eone.ewf.engine.model.SpawnMode;

import java.util.List;

/** Richieste e risposte delle operazioni del motore. */
public final class Requests {
    private Requests() {}

    public enum Action { OK, KO }

    /** value opzionale: se null lo calcola l'adapter del tipo documento. */
    /** starterId null = richiedente sconosciuto (utente SAP non mappato): i suoi task vanno al team di riserva. */
    public record Start(String processType, String documentKey, String backend, Long starterId, Money value) {}

    /** Cosa e' successo a un documento nel sistema di origine (riga della coda degli avvii). */
    public enum DocumentEventType {
        /** Documento nuovo da approvare: eWF sceglie il processo con le regole di avvio. */
        CREATED,
        /** Il sistema di origine ha azzerato il rilascio (modifica rilevante secondo la strategia): si riapprova. */
        RESET,
        /** Documento modificato senza strategia di rilascio: eWF confronta valore e quantita' con l'avvio. */
        CHANGED,
        /** Documento cancellato: i processi aperti si annullano. */
        DELETED,
        /**
         * Evento neutro: "il documento e' stato toccato". eWF decide dallo stato del documento (fotografia
         * dell'evento, o lettura) e dei suoi processi: avvio, riavvio, annullamento o niente.
         */
        TOUCHED
    }

    /**
     * Riga della coda degli avvii.
     * isNew: documento creato in quel salvataggio (un fatto riportato dal sistema di origine).
     * snapshot: fotografia del documento al salvataggio (valore, attributi, posizioni). Se presente, eWF la usa
     *   al posto della lettura tramite adapter: niente chiamate di ritorno al sistema di origine.
     *   snapshot.exists() == false: documento cancellato.
     */
    public record DocumentEvent(String queueId, String docType, String documentKey, String backend,
                                DocumentEventType type, String sapUser, boolean isNew,
                                it.eone.ewf.engine.spi.DocumentAdapter.DocumentInfo snapshot) {
        public DocumentEvent(String queueId, String docType, String documentKey, String backend,
                             DocumentEventType type, String sapUser) {
            this(queueId, docType, documentKey, backend, type, sapUser, false, null);
        }
    }

    public record EventResult(String queueId, EventStatus status, String message, Long processId) {
        public enum EventStatus { DONE, WARNING, ERROR }
    }

    /** expectedRowVersion opzionale: se valorizzato, protegge da doppio click / due utenti insieme. */
    public record Act(long taskId, long actorId, Action action, String note, Integer expectedRowVersion) {}

    /** Scelta di un'opzione su un nodo di scelta (al posto di Approva/Respingi). */
    public record Choose(long taskId, long actorId, String optionCode, String note, Integer expectedRowVersion) {}

    public record Outcome(Result result, String message, ProcessStatus processStatus, List<Long> newTaskIds) {
        public enum Result { DONE, REJECTED, UNKNOWN }
    }

    public record PreviewEntry(int stepNo, String taskText, String roleCode, String assignee, String note) {}

    /** Opzione proposta all'utente su un nodo di scelta. */
    public record OptionEntry(String code, String label, boolean reject, String startProcess, SpawnMode spawnMode) {}

    /**
     * Una tappa del percorso del processo, per il dettaglio a video.
     * DONE: step concluso (who = chi ha agito, note = opzione scelta o "per conto di");
     * CURRENT: task aperto; PLANNED: step previsto (who = assegnatario previsto, se determinabile oggi);
     * CHOICE: qui si scegliera' come proseguire (options = etichette delle opzioni); oltre non e' determinato.
     */
    public record PathEntry(int stepNo, String taskText, String roleCode, String who, Kind kind, String note,
                            List<String> options) {
        public enum Kind { DONE, CURRENT, PLANNED, CHOICE }
    }

    public record InboxEntry(long taskId, long processId, Reason reason) {
        public enum Reason { OWN, SUBSTITUTE, TEAM }
    }
}
