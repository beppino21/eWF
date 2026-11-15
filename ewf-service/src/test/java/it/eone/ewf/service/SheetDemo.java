package it.eone.ewf.service;

import it.eone.ewf.engine.Requests.PathEntry;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.service.Views.*;
import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.sheet.DocumentSheet.*;
import it.eone.ewf.service.sheet.DocumentSheet.Column;
import it.eone.ewf.service.sheet.SheetPdf;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Schede di prova con dati finti: java ... SheetDemo <cartella>. Serve a giudicare l'impaginazione. */
public final class SheetDemo {

    static final ZoneId ROME = ZoneId.of("Europe/Rome");

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : ".");
        ProcessDetail d = detail();
        DocumentSheet oda = poSheet().with(SheetSections.approval(d, ROME));
        Files.write(dir.resolve("scheda_oda_prova.pdf"), new SheetPdf(ROME).render(oda, Instant.parse("2026-11-15T10:30:00Z")));
        DocumentSheet ewf = SheetSections.ewfView(d, List.of(new Views.Column("Fornitore", "sap.LIFNR"),
                new Views.Column("Ragione sociale", "sap.LIFNR_TXT"), new Views.Column("Divisione", "sap.WERKS_TXT"),
                new Views.Column("Gruppo acquisti", "sap.EKGRP_TXT"), new Views.Column("Tipo OdA", "sap.BSART")), ROME);
        Files.write(dir.resolve("riepilogo_approvazione_prova.pdf"), new SheetPdf(ROME).render(ewf, Instant.parse("2026-11-15T10:30:00Z")));
    }

    /** Come la costruira' la vista "Scheda OdA" da BAPI_PO_GETDETAIL1. */
    static DocumentSheet poSheet() {
        List<Section> s = new ArrayList<>();
        s.add(new Fields("Fornitore", 3, List.of(
                Field.wide("Ragione sociale", "Acme Forniture Industriali S.p.A. (0011000141)", 2),
                Field.of("Partita IVA", "IT01234567890"),
                Field.wide("Indirizzo", "Via dell'Industria 12 - 42015 Correggio (RE)", 2),
                Field.of("Contatto", "Ufficio vendite - tel. 0522 123456"))));
        s.add(new Fields("Ordine", 4, List.of(
                Field.of("Tipo", "ZNB - OdA standard"), Field.of("Data documento", "12/11/2026"),
                Field.of("Società", "S100 - e-One Demo S.r.l."), Field.of("Divisa", "EUR"),
                Field.of("Org. acquisti", "OA01"), Field.of("Gruppo acquisti", "Z10 - Acquisti indiretti"),
                Field.of("Creato da", "MROSSI"), Field.of("Valore netto", "18.725,20 EUR"),
                Field.wide("Condizioni di pagamento", "B060 - Bonifico 60 gg data fattura fine mese", 2),
                Field.wide("Resa (Incoterms)", "DAP - Correggio, magazzino centrale", 2))));
        List<Row> items = List.of(
                Row.of("10", "MAT-100245", "Guanti antitaglio livello 5, taglia 9", "1.200", "PA", "3,85", "4.620,00", "20/11/2026", "D030")
                        .withNote("Consegna in cartoni da 100 paia, etichettati per reparto."),
                Row.of("20", "MAT-100311", "Occhiali di protezione con lente antiappannante", "350", "PZ", "12,40", "4.340,00", "20/11/2026", "D030"),
                Row.of("30", "", "Manutenzione straordinaria impianto aspirazione linea 2", "1", "AU", "8.900,00", "8.900,00", "30/11/2026", "D030")
                        .withNote("Centro di costo 4100 - Manutenzione stabilimento. Intervento da concordare con il capo reparto."),
                Row.of("40", "MAT-100980", "Cartelli di sicurezza in alluminio 30x40", "24", "PZ", "36,05", "865,20", "20/11/2026", "D030"));
        s.add(new Table("Posizioni", List.of(
                Column.center("Pos.", 5), Column.left("Materiale", 12), Column.left("Descrizione", 33),
                Column.right("Quantità", 8), Column.center("UM", 5), Column.right("Prezzo", 10),
                Column.right("Valore", 11), Column.center("Consegna", 10), Column.center("Div.", 6)),
                items, List.of("", "", "Totale", "", "", "", "18.725,20", "", ""), null));
        s.add(new Text("Testi di testata", """
                Fornitura urgente per adeguamento DPI richiesto dal RSPP.
                Si prega di confermare la data di consegna entro 48 ore dal ricevimento dell'ordine."""));
        return new DocumentSheet("Ordine d'acquisto 4500001064", "e-One Demo S.r.l. - Divisione D030 Stabilimento Correggio",
                "OdA 4500001064", s);
    }

    static ProcessDetail detail() {
        Instant t0 = Instant.parse("2026-11-12T08:15:00Z");
        Header h = new Header(8000000013L, "EONE_ODA", "Autorizzazione - Rilascio OdA", "F", "4500001064", null,
                "RUNNING", null, new Money(new BigDecimal("18725.20"), "EUR"), "Mario Rossi", t0, null, "PROCESS",
                Map.of("sap.LIFNR", "0011000141", "sap.LIFNR_TXT", "Acme Forniture Industriali S.p.A.",
                        "sap.WERKS_TXT", "Stabilimento Correggio", "sap.EKGRP_TXT", "Acquisti indiretti", "sap.BSART", "ZNB"));
        List<PathEntry> path = List.of(
                new PathEntry(10, "Avvio processo Autorizz. ODA", null, "Mario Rossi", PathEntry.Kind.DONE, null, List.of()),
                new PathEntry(20, "Approvazione capo reparto", "CAPOREP", "Anna Bianchi", PathEntry.Kind.DONE, null, List.of()),
                new PathEntry(30, "Approvazione direttore di stabilimento", "DIRSTAB", "Luca Verdi", PathEntry.Kind.CURRENT, null, List.of()),
                new PathEntry(40, "Approvazione amministratore delegato", "AD", "Sara Neri", PathEntry.Kind.PLANNED, null, List.of()));
        List<HistoryEntry> hist = List.of(
                new HistoryEntry(1, 10, "Avvio processo Autorizz. ODA", "OK", null, "Mario Rossi", null, null, null, t0, t0.plusSeconds(60)),
                new HistoryEntry(2, 20, "Approvazione capo reparto", "OK", null, "Paolo Gialli", "Anna Bianchi", null,
                        "Ok, ma verificare i tempi dell'intervento in pos. 30", t0, Instant.parse("2026-11-13T15:40:00Z")));
        List<Comment> comments = List.of(
                new Comment(1, "Mario Rossi", "Urgente: i guanti attuali sono fuori norma da fine mese.", Instant.parse("2026-11-12T08:20:00Z"), false),
                new Comment(2, "Luca Verdi", "Chiesto al fornitore uno sconto sulla manutenzione, attendo risposta.", Instant.parse("2026-11-14T09:05:00Z"), false));
        return new ProcessDetail(h, path, hist, comments, null, new Links(List.of(), false, false));
    }
}
