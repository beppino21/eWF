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
        DELETED
    }

    public record DocumentEvent(String queueId, String docType, String documentKey, String backend,
                                DocumentEventType type, String sapUser) {}

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

    public record InboxEntry(long taskId, long processId, Reason reason) {
        public enum Reason { OWN, SUBSTITUTE, TEAM }
    }
}
