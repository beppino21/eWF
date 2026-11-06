package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.Money;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/** Risolutore standard per assignee_mode = RULES (ex /EWF/WF_USER_TASK). */
public interface AssignmentResolver {

    record Query(String processType, String roleCode, Map<String, String> context, Money value, LocalDate date) {}

    record Assignment(Long userId, String teamCode) {}

    Optional<Assignment> resolve(Query query);
}
