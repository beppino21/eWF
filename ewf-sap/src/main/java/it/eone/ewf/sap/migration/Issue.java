package it.eone.ewf.sap.migration;

/** Un'osservazione del rapporto. ERROR blocca l'attivazione dell'oggetto; WARNING e INFO no. */
public record Issue(Severity severity, String area, String object, String message) {
    public enum Severity { ERROR, WARNING, INFO }

    public static Issue error(String area, String object, String msg) { return new Issue(Severity.ERROR, area, object, msg); }
    public static Issue warn(String area, String object, String msg) { return new Issue(Severity.WARNING, area, object, msg); }
    public static Issue info(String area, String object, String msg) { return new Issue(Severity.INFO, area, object, msg); }
}
