package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistenza del runtime. Gli update controllano rowVersion e lo incrementano:
 * se non coincide lanciano OptimisticLockException.
 */
public interface RuntimeStore {
    ProcessInstance insertProcess(ProcessInstance p);
    void updateProcess(ProcessInstance p);

    /**
     * Blocca il processo fino alla fine della transazione corrente (SELECT ... FOR UPDATE).
     * Serializza le chiusure dei task paralleli dello stesso processo.
     */
    void lockProcess(long processId);
    Optional<ProcessInstance> process(long id);
    boolean hasOpenProcess(String processType, String docType, String documentKey, String backend);

    TaskInstance insertTask(TaskInstance t);
    void updateTask(TaskInstance t);
    Optional<TaskInstance> task(long id);
    List<TaskInstance> tasksOf(long processId);
    List<TaskInstance> openTasksFor(Set<Long> assigneeIds, Set<String> teamCodes);

    Map<Integer, StepState> stepStates(long processId);
    void putStepState(StepState s);
    void clearStepStates(long processId, String reason);

    HandlerExecution insertExecution(HandlerExecution e);
    void updateExecution(HandlerExecution e);
    Optional<HandlerExecution> execution(long id);
    List<HandlerExecution> executionsOf(long processId);

    void insertDocument(GeneratedDocument d);
    List<GeneratedDocument> documentsOf(long processId);

    void insertLink(ProcessLink l);
    List<ProcessLink> links(long processId);

    /** Violazione del vincolo "un solo processo aperto per documento" (corsa tra due avvii). */
    final class DuplicateOpenProcessException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public DuplicateOpenProcessException(String msg) { super(msg); }
    }

    final class OptimisticLockException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public OptimisticLockException(String msg) { super(msg); }
    }
}
