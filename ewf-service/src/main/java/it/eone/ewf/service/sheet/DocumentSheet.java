package it.eone.ewf.service.sheet;

import java.util.ArrayList;
import java.util.List;

/**
 * Scheda di un documento, indipendente dal sistema di origine e dal tipo documento: chi la costruisce
 * (es. la vista "Scheda OdA" da BAPI_PO_GETDETAIL1, o da OData su Public Edition) riempie le sezioni,
 * {@link SheetPdf} la impagina. Cosi' l'impaginazione e' una sola per tutti i tipi e tutti i sistemi.
 *
 * title: "Ordine d'acquisto 4500001064"; subtitle: riga sotto il titolo (es. ragione sociale della societa');
 * reference: riferimento breve per il pie' di pagina.
 */
public record DocumentSheet(String title, String subtitle, String reference, List<Section> sections) {

    public DocumentSheet {
        sections = List.copyOf(sections);
    }

    /** Copia con altre sezioni in coda (es. la parte eWF). */
    public DocumentSheet with(List<Section> more) {
        List<Section> all = new ArrayList<>(sections);
        all.addAll(more);
        return new DocumentSheet(title, subtitle, reference, all);
    }

    public sealed interface Section permits Fields, Table, Text {}

    /**
     * Riquadri etichetta / valore, disposti su "columns" colonne. Un campo puo' occupare piu' colonne (span).
     * I campi con valore vuoto non vengono stampati.
     */
    public record Fields(String title, int columns, List<Field> fields) implements Section {
        public Fields {
            if (columns < 1) throw new IllegalArgumentException("columns >= 1");
            fields = List.copyOf(fields);
        }
    }

    public record Field(String label, String value, int span) {
        public static Field of(String label, String value) { return new Field(label, value, 1); }
        public static Field wide(String label, String value, int span) { return new Field(label, value, span); }
    }

    public enum Align { LEFT, CENTER, RIGHT }

    /** Colonna di tabella: larghezza relativa. */
    public record Column(String label, float width, Align align) {
        public static Column left(String label, float width) { return new Column(label, width, Align.LEFT); }
        public static Column right(String label, float width) { return new Column(label, width, Align.RIGHT); }
        public static Column center(String label, float width) { return new Column(label, width, Align.CENTER); }
    }

    /**
     * Tabella con intestazione ripetuta su ogni pagina. Una riga puo' avere una nota sotto, a tutta larghezza
     * (es. testo di posizione). total: riga finale in grassetto, puo' essere null.
     * emptyText: cosa scrivere se non ci sono righe (null = la sezione non si stampa).
     */
    public record Table(String title, List<Column> columns, List<Row> rows, List<String> total, String emptyText)
            implements Section {
        public Table {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
            total = total == null ? null : List.copyOf(total);
        }
    }

    public record Row(List<String> cells, String note) {
        public static Row of(String... cells) { return new Row(List.of(cells), null); }
        public Row withNote(String n) { return new Row(cells, n); }
    }

    /** Blocco di testo libero (testi di testata, commenti). Vuoto = non si stampa. */
    public record Text(String title, String text) implements Section {}
}
