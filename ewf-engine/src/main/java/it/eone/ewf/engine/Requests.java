package it.eone.ewf.engine;

import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.ProcessStatus;

import java.util.List;

/** Richieste e risposte delle operazioni del motore. */
public final class Requests {
    private Requests() {}

    public enum Action { OK, KO }

    /** value opzionale: se null lo calcola l'adapter del tipo documento. */
    public record Start(String processType, String documentKey, String backend, long starterId, Money value) {}

    /** expectedRowVersion opzionale: se valorizzato, protegge da doppio click / due utenti insieme. */
    public record Act(long taskId, long actorId, Action action, String note, Integer expectedRowVersion) {}

    /** Scelta di un'opzione su un nodo di scelta (al posto di Approva/Respingi). */
    public record Choose(long taskId, long actorId, String optionCode, String note, Integer expectedRowVersion) {}

    public record Outcome(Result result, String message, ProcessStatus processStatus, List<Long> newTaskIds) {
        public enum Result { DONE, REJECTED, UNKNOWN }
    }

    public record PreviewEntry(int stepNo, String taskText, String roleCode, String assignee, String note) {}

    /** Opzione proposta all'utente su un nodo di scelta. */
    public record OptionEntry(String code, String label, boolean reject) {}

    public record InboxEntry(long taskId, long processId, Reason reason) {
        public enum Reason { OWN, SUBSTITUTE, TEAM }
    }
}
