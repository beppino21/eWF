package it.eone.ewf.engine;

import java.time.ZoneId;

/**
 * fallbackTeamCode: team che riceve i task quando l'assegnatario non si trova
 * (utente non mappato, non attivo, nessuna regola). Il task non resta mai senza titolare.
 */
public record EngineConfig(String fallbackTeamCode, ZoneId zone) {
    public static EngineConfig defaults() {
        return new EngineConfig("WFADM", ZoneId.of("Europe/Rome"));
    }
}
