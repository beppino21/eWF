package it.eone.ewf.service;

import it.eone.ewf.engine.Requests.PathEntry;
import it.eone.ewf.engine.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dati restituiti all'interfaccia: sola lettura, gia' pronti da mostrare. */
public final class Views {
    private Views() {}

    /** Semaforo dei giorni di attesa (soglie per tipo di processo). */
    public enum Light { GREEN, YELLOW, RED }

    /** Perche' il task e' nella lista: mio, di un utente che sostituisco, del mio team. */
    public enum Reason { OWN, SUBSTITUTE, TEAM }

    /**
     * Riga della lista delle attivita'.
     * forWhom: per SUBSTITUTE il nome del titolare, per TEAM il codice del team, altrimenti null.
     * summary: riepilogo da list_summary del tipo di processo. attributes: attributi del documento piu' quelli
     * calcolati (documentKey, value, currency, starter, processType, processText): servono alle colonne
     * specifiche quando la lista e' filtrata su un tipo di processo.
     * webActionable: false per i task che si chiudono solo in SAP GUI (interaction diversa da WEB).
     */
    public record WorkItem(long taskId, long processId, String processType, String processTypeText,
                           String docType, String documentKey, String taskText, Reason reason, String forWhom,
                           String starter, Instant waitingSince, int daysWaiting, Light light, Money value,
                           String summary, Map<String, String> attributes, boolean webActionable) {}

    /** Colonna specifica di un tipo di processo: intestazione e chiave in WorkItem.attributes. */
    public record Column(String label, String key) {}

    /** Quanti task per tipo di processo, per il filtro. */
    public record TypeCount(String processType, String text, int count) {}

    /** typeCounts e' calcolato sulla lista non filtrata. */
    public record Worklist(List<WorkItem> items, List<TypeCount> typeCounts) {}

    public record WorklistFilter(String processType, String text) {
        public static WorklistFilter all() { return new WorklistFilter(null, null); }
    }

    /** Dettaglio di un processo. myTask: il task su cui l'utente puo' agire adesso, se c'e'. */
    public record ProcessDetail(Header header, List<PathEntry> path, List<HistoryEntry> history,
                                List<Comment> comments, MyTask myTask, Links links) {}

    public record Header(long processId, String processType, String processTypeText, String docType,
                         String documentKey, String text, String status, String statusReason, Money value,
                         String starter, Instant startedAt, Instant endedAt, String attachmentScope,
                         Map<String, String> attributes) {}

    /**
     * Task concluso. status: OK, KO, MOVED, CANCELLED, SKIPPED, ERROR. actedBy: chi ha agito davvero;
     * forWhom: il titolare, quando ha agito un sostituto; movedTo: destinatario di uno spostamento.
     */
    public record HistoryEntry(long taskId, int stepNo, String taskText, String status, String option,
                               String actedBy, String forWhom, String movedTo, String note,
                               Instant createdAt, Instant closedAt) {}

    public record Comment(long id, String author, String text, Instant createdAt, boolean mine) {}

    /**
     * Una barra del Gantt di un processo. who: chi ha agito o, se il task e' ancora aperto, a chi e' assegnato
     * (persona o "Team X"). status come HistoryEntry, piu' OPEN/EXECUTING per i task in corso e START per
     * l'avvio del processo. to null = ancora aperto.
     */
    public record TimelineBar(String who, String taskText, String status, String option, Instant from, Instant to,
                              String forWhom, String note) {}

    /**
     * Cosa l'utente puo' fare sul task. options non vuoto = nodo di scelta (al posto di Approva/Respingi).
     * asSubstitute: l'utente agirebbe per conto di forWhom. rowVersion: da ripassare alle azioni, cosi' se
     * un collega ha gia' firmato l'azione viene rifiutata invece di sovrascrivere.
     */
    public record MyTask(long taskId, String taskText, Reason reason, String forWhom, boolean webActionable,
                         List<Option> options, List<Function> functions, int rowVersion) {}

    public record Option(String code, String label, boolean reject) {}

    /** Funzione speciale del task. confirmText: se presente, chiedere conferma prima di eseguire. */
    public record Function(String code, String label, String confirmText) {}

    /**
     * Cosa e' disponibile per il documento. sheets: una voce (pulsante) per ogni scheda PDF; c'e' sempre
     * almeno "Riepilogo approvazione", che non richiede il sistema di origine.
     */
    public record Links(List<SheetView> sheets, boolean sapGui, boolean documentAttachments) {}

    /** Scheda PDF disponibile: codice da ripassare a documentSheet, etichetta del pulsante. */
    public record SheetView(String code, String label) {}

    /**
     * Allegato. id: "P:<n>" per quelli salvati in eWF, "D:<id>" per quelli del sistema di origine.
     * deletable: solo per gli allegati di processo caricati dall'utente, a processo aperto.
     */
    public record Attachment(String id, String fileName, String mimeType, Long sizeBytes, String uploadedBy,
                             Instant uploadedAt, boolean deletable) {}

    public record FileContent(String fileName, String mimeType, byte[] content) {}

    /** Utente proposto per lo spostamento di un task. */
    public record UserChoice(long userId, String login, String displayName) {}

    public record FunctionResult(boolean ok, String message) {}
}
