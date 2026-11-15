package it.eone.ewf.sap.sheet;

import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.sheet.DocumentSheet.*;
import it.eone.ewf.service.sheet.SheetPdf;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Scheda OdA da risposte RFC finte, nella forma in cui le restituisce JCo. */
public class PoSheetTest {

    /** Risposte preparate per funzione; le funzioni non previste "non esistono". */
    static final class FakeRfc implements Rfc {
        final Map<String, Map<String, Map<String, String>>> structures = new HashMap<>();
        final Map<String, Map<String, List<Map<String, String>>>> tables = new HashMap<>();
        final Set<String> down = new HashSet<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public Result call(String function, Map<String, String> imports) {
            calls.add(function);
            if (down.contains(function)) throw new IllegalStateException("RFC_COMMUNICATION_FAILURE");
            if (!structures.containsKey(function) && !tables.containsKey(function)) throw new FunctionMissing(function);
            return new Result() {
                public Map<String, String> structure(String n) { return structures.getOrDefault(function, Map.of()).getOrDefault(n, Map.of()); }
                public List<Map<String, String>> table(String n) { return tables.getOrDefault(function, Map.of()).getOrDefault(n, List.of()); }
            };
        }

        void struct(String f, String name, String... kv) { structures.computeIfAbsent(f, k -> new HashMap<>()).put(name, map(kv)); }
        void row(String f, String table, String... kv) {
            tables.computeIfAbsent(f, k -> new HashMap<>()).computeIfAbsent(table, k -> new ArrayList<>()).add(map(kv));
        }
        static Map<String, String> map(String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return m;
        }
    }

    static FakeRfc sap() {
        FakeRfc r = new FakeRfc();
        String B = "BAPI_PO_GETDETAIL1";
        r.struct(B, "POHEADER", "PO_NUMBER", "4500001064", "COMP_CODE", "S100", "DOC_TYPE", "ZNB", "VENDOR", "0011000141",
                "PURCH_ORG", "OA01", "PUR_GROUP", "Z10", "CURRENCY", "EUR", "DOC_DATE", "2026-11-12", "CREATED_BY", "MROSSI",
                "PMNTTRMS", "B060", "INCOTERMS1", "DAP", "INCOTERMS2", "Correggio", "OUR_REF", "", "REF_1", "Offerta 2026/118");
        r.row(B, "RETURN", "TYPE", "S", "MESSAGE", "ok");
        r.row(B, "POITEM", "PO_ITEM", "00010", "DELETE_IND", "", "SHORT_TEXT", "Guanti antitaglio livello 5", "MATERIAL", "",
                "MATERIAL_LONG", "000000000000100245", "PLANT", "D030", "QUANTITY", "1200.000", "PO_UNIT", "PA",
                "NET_PRICE", "3.85", "PRICE_UNIT", "1", "ORDERPR_UN", "PA");
        r.row(B, "POITEM", "PO_ITEM", "00020", "DELETE_IND", "L", "SHORT_TEXT", "Posizione cancellata", "MATERIAL", "X",
                "PLANT", "D030", "QUANTITY", "5.000", "PO_UNIT", "PZ", "NET_PRICE", "100.00", "PRICE_UNIT", "1");
        r.row(B, "POITEM", "PO_ITEM", "00030", "DELETE_IND", "", "SHORT_TEXT", "Manutenzione impianto aspirazione", "MATERIAL", "",
                "PLANT", "D030", "QUANTITY", "1.000", "PO_UNIT", "AU", "NET_PRICE", "8900.00", "PRICE_UNIT", "1", "ORDERPR_UN", "AU");
        r.row(B, "POITEM", "PO_ITEM", "00040", "DELETE_IND", "", "SHORT_TEXT", "Cavo rame 3x2,5", "MATERIAL", "MP0000000030000185",
                "PLANT", "D030", "QUANTITY", "250.000", "PO_UNIT", "M", "NET_PRICE", "185.00", "PRICE_UNIT", "100", "ORDERPR_UN", "M");
        r.row(B, "POSCHEDULE", "PO_ITEM", "00010", "DELIVERY_DATE", "2026-11-25");
        r.row(B, "POSCHEDULE", "PO_ITEM", "00010", "DELIVERY_DATE", "2026-11-20");
        r.row(B, "POSCHEDULE", "PO_ITEM", "00030", "DELIVERY_DATE", "20261130");
        r.row(B, "POACCOUNT", "PO_ITEM", "00030", "COSTCENTER", "0000004100", "WBS_ELEMENT", "", "ORDERID", "", "GL_ACCOUNT", "0000650100");
        r.row(B, "POTEXTITEM", "PO_ITEM", "00010", "TEXT_ID", "F01", "TEXT_LINE", "Consegna in cartoni da 100 paia,");
        r.row(B, "POTEXTITEM", "PO_ITEM", "00010", "TEXT_ID", "F01", "TEXT_LINE", "etichettati per reparto.");
        r.row(B, "POTEXTHEADER", "TEXT_ID", "F01", "TEXT_LINE", "Fornitura urgente per adeguamento DPI.");
        r.row(B, "POTEXTHEADER", "TEXT_ID", "F01", "TEXT_LINE", "Confermare la consegna entro 48 ore.");
        r.row(B, "POTEXTHEADER", "TEXT_ID", "F01", "TEXT_LINE", "");
        r.row("/EWF/DOC_INFO", "ET_ITEMS", "ITEM", "00010", "NETWR", "4620.00");
        r.row("/EWF/DOC_INFO", "ET_ITEMS", "ITEM", "00030", "NETWR", "8900.00");
        r.row("/EWF/DOC_INFO", "ET_ITEMS", "ITEM", "00040", "NETWR", "462.50");
        r.struct("BAPI_VENDOR_GETDETAIL", "GENERALDETAIL", "NAME", "Acme Forniture Industriali S.p.A.", "STREET", "Via dell'Industria 12",
                "POSTL_CODE", "42015", "CITY", "Correggio", "REGION", "RE", "COUNTRY", "IT", "TELEPHONE", "0522 123456");
        r.struct("BAPI_COMPANYCODE_GETDETAIL", "COMPANYCODE_DETAIL", "COMP_NAME", "e-One Demo S.r.l.");
        return r;
    }

    static Map<String, String> ctx() {
        return Map.of("sap.LIFNR_TXT", "Acme (da eWF)", "sap.WERKS_TXT", "Stabilimento Correggio", "sap.EKGRP_TXT", "Acquisti indiretti",
                "sap.BSART_TXT", "OdA standard", "sap.ZTERM_TXT", "Bonifico 60 gg d.f.f.m.");
    }

    @Test
    public void schedaCompletaDaBapi() throws Exception {
        DocumentSheet s = new PoSheet(sap()).build("4500001064", ctx());
        assertEquals("Ordine d'acquisto 4500001064", s.title());
        assertEquals("e-One Demo S.r.l. - Divisione D030 Stabilimento Correggio", s.subtitle());

        Fields vendor = (Fields) s.sections().get(0);
        assertEquals("Acme Forniture Industriali S.p.A. (11000141)", vendor.fields().get(0).value(), "nome da SAP, codice senza zeri");
        assertEquals("Via dell'Industria 12 - 42015 Correggio (RE)", vendor.fields().get(2).value());
        Fields order = (Fields) s.sections().get(1);
        assertEquals("12/11/2026", field(order, "Data documento"));
        assertEquals("Z10 - Acquisti indiretti", field(order, "Gruppo acquisti"), "descrizione salvata da eWF");
        assertEquals("13.982,50 EUR", field(order, "Valore netto"));
        assertEquals("", field(order, "Nostro riferimento"));
        assertEquals("ZNB - OdA standard", field(order, "Tipo"));
        assertEquals("B060 - Bonifico 60 gg d.f.f.m.", field(order, "Condizioni di pagamento"));
        assertEquals("ZNB", field((Fields) new PoSheet(sap()).build("4500001064", Map.of()).sections().get(1), "Tipo"),
                "processo avviato prima delle descrizioni: solo il codice");

        Table items = (Table) s.sections().get(2);
        assertEquals(List.of("10", "30", "40"), items.rows().stream().map(r -> r.cells().get(0)).toList(), "la cancellata non c'e'");
        Row r10 = items.rows().get(0);
        assertEquals("100245", r10.cells().get(1), "MATERIAL_LONG, zeri iniziali tolti");
        assertEquals("1.200", r10.cells().get(3));
        assertEquals("3,85", r10.cells().get(5));
        assertEquals("4.620,00", r10.cells().get(6));
        assertEquals("20/11/2026", r10.cells().get(7), "la prima data di consegna");
        assertEquals("Consegna in cartoni da 100 paia, etichettati per reparto.", r10.note());
        assertEquals("Centro di costo 4100, Conto 650100", items.rows().get(1).note());
        assertEquals("30/11/2026", items.rows().get(1).cells().get(7));
        assertEquals("185,00 / 100 M", items.rows().get(2).cells().get(5), "prezzo per 100 metri");
        assertEquals("13.982,50", items.total().get(6));

        Text t = (Text) s.sections().get(3);
        assertEquals("Fornitura urgente per adeguamento DPI.\nConfermare la consegna entro 48 ore.", t.text());

        Path out = Path.of(System.getProperty("java.io.tmpdir"), "scheda_oda_bapi_finta.pdf");
        Files.write(out, new SheetPdf(ZoneId.of("Europe/Rome")).render(s, Instant.parse("2026-11-15T10:30:00Z")));
    }

    @Test
    public void funzioniFacoltativeAssentiOGiu() {
        FakeRfc r = sap();
        r.structures.remove("BAPI_VENDOR_GETDETAIL");                      // non esiste nel sistema
        r.down.add("BAPI_COMPANYCODE_GETDETAIL");                           // va in errore
        r.down.add("/EWF/DOC_INFO");
        DocumentSheet s = new PoSheet(r).build("4500001064", ctx());
        Fields vendor = (Fields) s.sections().get(0);
        assertEquals("Acme (da eWF) (11000141)", vendor.fields().get(0).value(), "ripiego sul nome salvato da eWF");
        assertEquals("", vendor.fields().get(2).value(), "niente indirizzo");
        assertEquals("S100", field((Fields) s.sections().get(1), "Società"));
        Table items = (Table) s.sections().get(2);
        assertEquals("4.620,00", items.rows().get(0).cells().get(6), "valore calcolato da quantita' e prezzo");
        assertEquals("462,50", items.rows().get(2).cells().get(6), "250 m x 185 / 100");

        FakeRfc kg = sap();
        kg.down.add("/EWF/DOC_INFO");
        kg.tables.get("BAPI_PO_GETDETAIL1").get("POITEM").get(3).put("ORDERPR_UN", "KG");   // prezzo al kg, ordine in metri
        Table t2 = (Table) new PoSheet(kg).build("4500001064", ctx()).sections().get(2);
        assertEquals("", t2.rows().get(2).cells().get(6), "unita' di prezzo diversa: senza conversione non si inventa");
        assertEquals("185,00 / 100 KG", t2.rows().get(2).cells().get(5));
        assertTrue(s.subtitle().startsWith("S100"));
    }

    @Test
    public void erroreDellaBapiDiventaMessaggio() {
        FakeRfc r = new FakeRfc();
        r.row("BAPI_PO_GETDETAIL1", "RETURN", "TYPE", "E", "MESSAGE", "Documento 4599999999 non esiste");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PoSheet(r).build("4599999999", Map.of()));
        assertTrue(e.getMessage().contains("non esiste"));
        FakeRfc down = new FakeRfc();
        down.down.add("BAPI_PO_GETDETAIL1");
        assertThrows(IllegalStateException.class, () -> new PoSheet(down).build("4500001064", Map.of()));
    }

    @Test
    public void formati() {
        assertEquals("11000141", PoSheet.alphaOut("0011000141"));
        assertEquals("ABC-01", PoSheet.alphaOut("ABC-01"));
        assertEquals("0", PoSheet.alphaOut("0000"));
        assertEquals("10", PoSheet.item("00010"));
        assertEquals("-12,50", PoSheet.num("12.50-", 2, 2), "segno in coda");
        assertEquals("01/02/2026", PoSheet.date("2026-02-01"));
        assertEquals("01/02/2026", PoSheet.date("20260201"));
        assertEquals("", PoSheet.date("00000000"));
        assertEquals("", PoSheet.date("0000-00-00"));
    }

    static String field(Fields f, String label) {
        return f.fields().stream().filter(x -> x.label().equals(label)).map(Field::value).findFirst().orElseThrow();
    }
}
