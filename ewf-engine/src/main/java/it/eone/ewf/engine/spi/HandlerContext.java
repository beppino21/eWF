package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.HookEvent;
import it.eone.ewf.engine.model.Money;

import java.util.Map;
import java.util.UUID;

/**
 * Richiesta a un handler. Contiene solo copie: l'handler non puo' modificare lo stato del motore.
 * event e' null per ASSIGNEE e PROCESS_END.
 */
public record HandlerContext(
        UUID idempotencyKey,
        Contract contract,
        HookEvent event,
        String action,
        ProcessRef process,
        TaskRef task,
        Actor actor,
        Map<String, String> params) {

    public record ProcessRef(long id, String processType, String docType, String documentKey,
                             String backend, UUID correlationKey, Money value) {}

    public record TaskRef(long id, int stepNo, int subopNo, String role, String taskParam) {}

    public record Actor(Long userId, String login, String sapUser, boolean asSubstitute, boolean system) {
        public static Actor systemActor() { return new Actor(null, "SYSTEM", null, false, true); }
    }
}
