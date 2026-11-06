package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.User;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

public interface UserDirectory {
    Optional<User> byId(long id);
    Optional<User> byLogin(String login);
    Optional<User> bySapUser(String sapUser);

    /** Peso per tipo processo se definito (ex USR_AUTH.LIV_AUT), altrimenti peso utente (ex PUNTEGGIO). */
    int effectiveWeight(long userId, String processType);

    /** true se substituteId sostituisce userId alla data. */
    boolean isSubstitute(long substituteId, long userId, LocalDate date);

    Set<Long> teamMembers(String teamCode, LocalDate date);

    Set<String> teamsOf(long userId, LocalDate date);

    /** Utenti che substituteId sta sostituendo alla data. */
    Set<Long> substitutedBy(long substituteId, LocalDate date);
}
