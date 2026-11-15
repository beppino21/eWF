package it.eone.ewf.service;

import it.eone.ewf.engine.Requests.PathEntry;
import it.eone.ewf.service.Views.*;
import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.sheet.DocumentSheet.*;
import it.eone.ewf.service.sheet.DocumentSheet.Column;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sezioni eWF della scheda: approvazioni gia' date, prossimi passi, commenti. Si aggiungono in coda a
 * qualunque vista, cosi' la scheda e' anche il documento di approvazione del processo.
 * Qui anche la vista "Riepilogo approvazione", disponibile per ogni tipo documento senza il sistema di origine.
 */
final class SheetSections {
    private SheetSections() {}

    static final String EWF_VIEW = "EWF";
    static final String EWF_VIEW_LABEL = "Riepilogo approvazione";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    static List<Section> approval(ProcessDetail d, ZoneId zone) {
        List<Section> out = new ArrayList<>();

        List<Row> done = new ArrayList<>();
        for (HistoryEntry h : d.history()) {
            String who = nvl(h.actedBy()) + (h.forWhom() == null ? "" : " per conto di " + h.forWhom());
            String outcome = outcome(h);
            if (h.movedTo() != null) outcome += " a " + h.movedTo();
            Row r = Row.of(ts(h.closedAt(), zone), h.taskText(), outcome, who.isBlank() ? "-" : who);
            done.add(h.note() == null ? r : r.withNote("Nota: " + h.note()));
        }
        out.add(new Table("Approvazioni", List.of(
                Column.center("Data", 16), Column.left("Attività", 32), Column.left("Esito", 20), Column.left("Chi", 32)),
                done, null, "Nessuna attività ancora conclusa."));

        List<Row> next = new ArrayList<>();
        for (PathEntry p : d.path()) {
            switch (p.kind()) {
                case CURRENT -> next.add(Row.of(p.taskText(), "In attesa", nvl(p.who())));
                case PLANNED -> next.add(Row.of(p.taskText(), "Previsto", nvl(p.who())));
                case CHOICE -> next.add(Row.of(p.taskText(), "Scelta: " + String.join(" / ", p.options()),
                        "il seguito dipende dalla scelta"));
                default -> { }
            }
        }
        if (!next.isEmpty())
            out.add(new Table("Prossimi passi", List.of(
                    Column.left("Attività", 40), Column.left("Stato", 28), Column.left("Chi", 32)), next, null, null));

        StringBuilder c = new StringBuilder();
        for (Comment cm : d.comments())
            c.append(ts(cm.createdAt(), zone)).append("  ").append(nvl(cm.author())).append(": ").append(cm.text()).append('\n');
        out.add(new Text("Commenti", c.toString()));
        return out;
    }

    /** Vista sempre disponibile: dati del processo e attributi del documento salvati all'avvio. */
    static DocumentSheet ewfView(ProcessDetail d, List<Views.Column> columns, ZoneId zone) {
        Header h = d.header();
        List<Field> f = new ArrayList<>();
        f.add(Field.of("Processo", h.processTypeText()));
        f.add(Field.of("Documento", h.docType() + " " + h.documentKey()));
        f.add(Field.of("Stato", status(h.status())));
        if (h.value() != null)
            f.add(Field.of("Valore", EwfService.formatAmount(h.value().amount()) + " " + h.value().currency()));
        f.add(Field.of("Avviato da", h.starter()));
        f.add(Field.of("Avviato il", ts(h.startedAt(), zone)));
        if (h.endedAt() != null) f.add(Field.of("Concluso il", ts(h.endedAt(), zone)));
        if (h.text() != null) f.add(Field.wide("Descrizione", h.text(), 3));

        List<Field> attrs = new ArrayList<>();
        Map<String, String> a = h.attributes();
        if (!columns.isEmpty()) {
            for (Views.Column col : columns)
                if (!List.of("value", "currency").contains(col.key())) attrs.add(Field.of(col.label(), a.get(col.key())));
        } else {
            a.forEach((k, v) -> { if (k.startsWith("sap.")) attrs.add(Field.of(k.substring(4), v)); });
        }
        List<Section> s = new ArrayList<>();
        s.add(new Fields(null, 3, f));
        s.add(new Fields("Dati del documento", 3, attrs));
        s.addAll(approval(d, zone));
        return new DocumentSheet(h.processTypeText() + " - " + h.documentKey(), null,
                h.docType() + " " + h.documentKey() + " - processo " + h.processId(), s);
    }

    private static String outcome(HistoryEntry h) {
        String s = switch (nvl(h.status())) {
            case "OK" -> "Approvato";
            case "KO" -> "Respinto";
            case "MOVED" -> "Spostato";
            case "CANCELLED" -> "Annullato";
            case "SKIPPED" -> "Non necessario";
            case "ERROR" -> "Errore";
            default -> h.status();
        };
        return h.option() == null ? s : h.option();
    }

    private static String status(String s) {
        return switch (nvl(s)) {
            case "RUNNING" -> "In approvazione";
            case "SUSPENDED" -> "Sospeso";
            case "COMPLETING", "COMPLETED" -> "Approvato";
            case "FORWARDED" -> "Passato ad altro processo";
            case "ERROR" -> "In errore";
            case "REJECTED" -> "Respinto";
            case "CANCELLED" -> "Annullato";
            default -> s;
        };
    }

    private static String ts(Instant i, ZoneId zone) {
        return i == null ? "" : TS.format(i.atZone(zone));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
