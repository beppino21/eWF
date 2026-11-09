package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.Money;

import java.util.List;
import java.util.Map;

/** Risposta di un handler. */
public record HandlerResult(
        Status status,
        String message,
        List<DocRef> documents,
        List<UserRef> users,
        List<Directive> directives,
        Map<String, String> output,
        boolean replayed) {

    public enum Status { OK, KO, UNKNOWN }

    public HandlerResult {
        documents = documents == null ? List.of() : List.copyOf(documents);
        users = users == null ? List.of() : List.copyOf(users);
        directives = directives == null ? List.of() : List.copyOf(directives);
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    public static HandlerResult ok() { return new HandlerResult(Status.OK, null, null, null, null, null, false); }
    public static HandlerResult ko(String msg) { return new HandlerResult(Status.KO, msg, null, null, null, null, false); }
    public static HandlerResult unknown(String msg) { return new HandlerResult(Status.UNKNOWN, msg, null, null, null, null, false); }
    public static HandlerResult users(UserRef... u) { return new HandlerResult(Status.OK, null, null, List.of(u), null, null, false); }
    public static HandlerResult withDirectives(Directive... d) { return new HandlerResult(Status.OK, null, null, null, List.of(d), null, false); }
    public static HandlerResult withDocuments(DocRef... d) { return new HandlerResult(Status.OK, null, List.of(d), null, null, null, false); }

    public record DocRef(String docCat, String docNumber, String docYear, String companyCode) {}

    /** EWF = login eWF; SAP = utente SAP da convertire con la mappatura. */
    public record UserRef(IdType idType, String userId) {
        public enum IdType { EWF, SAP }
        public static UserRef ewf(String login) { return new UserRef(IdType.EWF, login); }
        public static UserRef sap(String user) { return new UserRef(IdType.SAP, user); }
    }

    /** Le sole istruzioni che un handler puo' dare al motore. */
    public sealed interface Directive permits SkipStep, SkipTask, SetValue, SelectOption {}
    public record SkipStep(int stepNo) implements Directive {}
    public record SkipTask() implements Directive {}
    public record SetValue(Money value) implements Directive {}
    /** In ON_ACTIVATE di un nodo di scelta: sceglie l'opzione al posto dell'utente (instradamento automatico). */
    public record SelectOption(String optionCode) implements Directive {}
}
