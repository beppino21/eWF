package it.eone.ewf.sap.sheet;

import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.sheet.DocumentSheet.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Vista "Scheda OdA" per R/3 e S/4HANA on-premise, con sole funzioni standard o gia' di eWF:
 *  - BAPI_PO_GETDETAIL1: testata, posizioni, testi, imputazioni, piano di consegna;
 *  - /EWF/DOC_INFO: valore netto per posizione (lo stesso usato dal motore per le soglie);
 *  - BAPI_VENDOR_GETDETAIL, BAPI_COMPANYCODE_GETDETAIL: indirizzo fornitore e nome societa'
 *    (facoltative: se mancano o falliscono la scheda esce lo stesso, con i soli codici).
 * Le descrizioni gia' salvate da eWF all'avvio (sap.LIFNR_TXT, sap.WERKS_TXT, sap.EKGRP_TXT, sap.BSART_TXT,
 * sap.ZTERM_TXT) si usano cosi' come sono; per i processi avviati prima che l'estrattore le portasse restano i codici.
 */
public final class PoSheet {

    private static final DateTimeFormatter IT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Rfc rfc;

    public PoSheet(Rfc rfc) {
        this.rfc = rfc;
    }

    public DocumentSheet build(String ebeln, Map<String, String> context) {
        Map<String, String> ctx = context == null ? Map.of() : context;
        Rfc.Result po = rfc.call("BAPI_PO_GETDETAIL1", Map.of(
                "PURCHASEORDER", ebeln, "ITEM_TEXT", "X", "HEADER_TEXT", "X", "ACCOUNT_ASSIGNMENT", "X"));
        for (Map<String, String> r : po.table("RETURN"))
            if ("E".equals(r.get("TYPE")) || "A".equals(r.get("TYPE")))
                throw new IllegalStateException("BAPI_PO_GETDETAIL1: " + r.get("MESSAGE"));
        Map<String, String> h = po.structure("POHEADER");
        if (v(h, "PO_NUMBER").isEmpty()) throw new IllegalStateException("Ordine d'acquisto " + ebeln + " non trovato");

        Map<String, BigDecimal> values = itemValues(ebeln);
        Map<String, String> vendor = optional("BAPI_VENDOR_GETDETAIL", Map.of("VENDORNO", v(h, "VENDOR")), "GENERALDETAIL");
        Map<String, String> company = optional("BAPI_COMPANYCODE_GETDETAIL", Map.of("COMPANYCODEID", v(h, "COMP_CODE")),
                "COMPANYCODE_DETAIL");
        String currency = v(h, "CURRENCY");

        // --- posizioni
        Map<String, String> firstDelivery = new HashMap<>();
        for (Map<String, String> s : po.table("POSCHEDULE")) {
            String item = item(v(s, "PO_ITEM"));
            String d = date(v(s, "DELIVERY_DATE"));
            if (!d.isEmpty()) firstDelivery.merge(item, d, (a, b) -> sortable(a).compareTo(sortable(b)) <= 0 ? a : b);
        }
        Map<String, List<String>> itemTexts = texts(po.table("POTEXTITEM"), true);
        Map<String, List<String>> accounts = new LinkedHashMap<>();
        for (Map<String, String> a : po.table("POACCOUNT")) accounts.computeIfAbsent(item(v(a, "PO_ITEM")), k -> new ArrayList<>()).add(account(a));

        List<Row> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Set<String> plants = new LinkedHashSet<>();
        for (Map<String, String> i : po.table("POITEM")) {
            if (!v(i, "DELETE_IND").isEmpty()) continue;                      // posizioni cancellate o bloccate
            String item = item(v(i, "PO_ITEM"));
            BigDecimal value = values.getOrDefault(item, computed(i));
            if (value != null) total = total.add(value);
            plants.add(v(i, "PLANT"));
            String material = !v(i, "MATERIAL_LONG").isEmpty() ? v(i, "MATERIAL_LONG") : v(i, "MATERIAL");
            Row r = Row.of(item, alphaOut(material), v(i, "SHORT_TEXT"), num(v(i, "QUANTITY"), 0, 3), v(i, "PO_UNIT"),
                    price(i), value == null ? "" : amount(value), firstDelivery.getOrDefault(item, ""), v(i, "PLANT"));
            List<String> note = new ArrayList<>();
            note.addAll(itemTexts.getOrDefault(item, List.of()));
            accounts.getOrDefault(item, List.of()).stream().filter(s -> !s.isEmpty()).forEach(note::add);
            rows.add(note.isEmpty() ? r : r.withNote(String.join("  -  ", note)));
        }

        // --- testata
        String vendorName = first(v(vendor, "NAME"), ctx.get("sap.LIFNR_TXT"));
        List<Section> s = new ArrayList<>();
        s.add(new Fields("Fornitore", 3, List.of(
                Field.wide("Ragione sociale", join(" ", vendorName, "(" + alphaOut(v(h, "VENDOR")) + ")"), 2),
                Field.of("Telefono", v(vendor, "TELEPHONE")),
                Field.wide("Indirizzo", address(vendor), 3))));
        String companyName = v(company, "COMP_NAME");
        s.add(new Fields("Ordine", 4, List.of(
                Field.of("Tipo", join(" - ", v(h, "DOC_TYPE"), ctx.get("sap.BSART_TXT"))),
                Field.of("Data documento", date(v(h, "DOC_DATE"))),
                Field.of("Società", join(" - ", v(h, "COMP_CODE"), companyName)),
                Field.of("Divisa", currency),
                Field.of("Org. acquisti", v(h, "PURCH_ORG")),
                Field.of("Gruppo acquisti", join(" - ", v(h, "PUR_GROUP"), ctx.get("sap.EKGRP_TXT"))),
                Field.of("Creato da", v(h, "CREATED_BY")),
                Field.of("Valore netto", rows.isEmpty() ? "" : amount(total) + " " + currency),
                Field.wide("Condizioni di pagamento", join(" - ", v(h, "PMNTTRMS"), ctx.get("sap.ZTERM_TXT")), 2),
                Field.wide("Resa (Incoterms)", join(" - ", v(h, "INCOTERMS1"), v(h, "INCOTERMS2")), 2),
                Field.wide("Nostro riferimento", v(h, "OUR_REF"), 2),
                Field.wide("Vostro riferimento", v(h, "REF_1"), 2))));
        s.add(new Table("Posizioni", List.of(
                Column.center("Pos.", 5), Column.left("Materiale", 19), Column.left("Descrizione", 24),
                Column.right("Quantità", 8), Column.center("UM", 5), Column.right("Prezzo", 13),
                Column.right("Valore", 11), Column.center("Consegna", 10), Column.center("Div.", 5)),
                rows, List.of("", "", "Totale", "", "", "", amount(total), "", ""), "Nessuna posizione attiva."));
        List<String> header = new ArrayList<>();
        texts(po.table("POTEXTHEADER"), false).values().forEach(header::addAll);
        s.add(new Text("Testi di testata", String.join("\n", header)));

        String plant = plants.size() == 1 ? plants.iterator().next() : null;
        String plantText = plant == null ? null : join(" ", "Divisione " + plant, ctx.get("sap.WERKS_TXT"));
        return new DocumentSheet("Ordine d'acquisto " + v(h, "PO_NUMBER"),
                join(" - ", first(companyName, v(h, "COMP_CODE")), plantText), "OdA " + v(h, "PO_NUMBER"), s);
    }

    /** Valori netti per posizione da /EWF/DOC_INFO; se non disponibile, si calcolano da quantita' e prezzo. */
    private Map<String, BigDecimal> itemValues(String ebeln) {
        Map<String, BigDecimal> out = new HashMap<>();
        try {
            for (Map<String, String> r : rfc.call("/EWF/DOC_INFO", Map.of("I_TIPODOC", "F", "I_DOCUMENT", ebeln)).table("ET_ITEMS")) {
                BigDecimal n = decimal(v(r, "NETWR"));
                if (n != null) out.put(item(v(r, "ITEM")), n);
            }
        } catch (RuntimeException e) {
            // facoltativo
        }
        return out;
    }

    private Map<String, String> optional(String function, Map<String, String> imports, String structure) {
        if (imports.values().stream().anyMatch(String::isEmpty)) return Map.of();
        try {
            return rfc.call(function, imports).structure(structure);
        } catch (RuntimeException e) {
            return Map.of();                                                  // facoltativa: si va avanti con i codici
        }
    }

    /** quantita' * prezzo / unita' di prezzo; vale solo se l'unita' di prezzo coincide con quella d'ordine. */
    private static BigDecimal computed(Map<String, String> i) {
        BigDecimal q = decimal(v(i, "QUANTITY")), p = decimal(v(i, "NET_PRICE")), per = decimal(v(i, "PRICE_UNIT"));
        String ou = v(i, "ORDERPR_UN");
        if (q == null || p == null || (!ou.isEmpty() && !ou.equals(v(i, "PO_UNIT")))) return null;
        if (per == null || per.signum() == 0) per = BigDecimal.ONE;
        return q.multiply(p).divide(per, 2, RoundingMode.HALF_UP);
    }

    private static String price(Map<String, String> i) {
        String p = num(v(i, "NET_PRICE"), 2, 5);
        if (p.isEmpty()) return "";
        BigDecimal per = decimal(v(i, "PRICE_UNIT"));
        String unit = first(v(i, "ORDERPR_UN"), v(i, "PO_UNIT"));
        boolean perOne = per == null || per.compareTo(BigDecimal.ONE) == 0;
        return perOne && unit.equals(v(i, "PO_UNIT")) ? p : p + " / " + (perOne ? "" : num(per.toPlainString(), 0, 3) + " ") + unit;
    }

    /** Testi raggruppati per posizione (item=true) o tutti insieme; righe vuote finali tolte. */
    private static Map<String, List<String>> texts(List<Map<String, String>> rows, boolean byItem) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            String key = byItem ? item(v(r, "PO_ITEM")) : v(r, "TEXT_ID");
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(v(r, "TEXT_LINE"));
        }
        out.values().forEach(l -> { while (!l.isEmpty() && l.get(l.size() - 1).isBlank()) l.remove(l.size() - 1); });
        if (byItem) out.replaceAll((k, l) -> List.of(String.join(" ", l).replaceAll("\\s+", " ").trim()));
        out.values().removeIf(l -> l.isEmpty() || (l.size() == 1 && l.get(0).isBlank()));
        return out;
    }

    private static String account(Map<String, String> a) {
        List<String> p = new ArrayList<>();
        if (!v(a, "COSTCENTER").isEmpty()) p.add("Centro di costo " + alphaOut(v(a, "COSTCENTER")));
        if (!v(a, "WBS_ELEMENT").isEmpty()) p.add("WBS " + v(a, "WBS_ELEMENT"));
        if (!v(a, "ORDERID").isEmpty()) p.add("Ordine " + alphaOut(v(a, "ORDERID")));
        if (!v(a, "ASSET_NO").isEmpty()) p.add("Cespite " + alphaOut(v(a, "ASSET_NO")));
        if (!v(a, "GL_ACCOUNT").isEmpty()) p.add("Conto " + alphaOut(v(a, "GL_ACCOUNT")));
        return String.join(", ", p);
    }

    private static String address(Map<String, String> a) {
        String town = join(" ", v(a, "POSTL_CODE"), v(a, "CITY"))
                + (v(a, "REGION").isEmpty() ? "" : " (" + v(a, "REGION") + ")");
        String country = v(a, "COUNTRY");
        return join(" - ", v(a, "STREET"), town.trim(), country.isEmpty() || "IT".equals(country) ? null : country);
    }

    // ------------------------------------------------------------------ formati

    static String v(Map<String, String> m, String k) {
        String s = m.get(k);
        return s == null ? "" : s.trim();
    }

    static String item(String s) {
        String t = s.replaceFirst("^0+(?=\\d)", "");
        return t.isEmpty() ? s : t;
    }

    /** Come la conversione ALPHA in uscita: zeri iniziali tolti solo se il codice e' tutto numerico. */
    static String alphaOut(String s) {
        return s.matches("\\d+") ? s.replaceFirst("^0+(?=\\d)", "") : s;
    }

    static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        if (t.endsWith("-")) t = "-" + t.substring(0, t.length() - 1);    // segno in coda, come in SAP
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String num(String s, int minDec, int maxDec) {
        BigDecimal b = decimal(s);
        if (b == null) return "";
        DecimalFormat f = new DecimalFormat("#,##0" + (maxDec == 0 ? "" : "." + "0".repeat(minDec) + "#".repeat(maxDec - minDec)),
                DecimalFormatSymbols.getInstance(Locale.ITALY));
        return f.format(b);
    }

    static String amount(BigDecimal b) {
        return num(b.toPlainString(), 2, 2);
    }

    /** Date SAP in qualunque formato JCo le restituisca (yyyy-MM-dd, yyyyMMdd, dd.MM.yyyy) -> dd/MM/yyyy. */
    static String date(String s) {
        if (s == null || s.isBlank() || s.matches("0+|0000-00-00")) return "";
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(s.substring(0, 10)).format(IT);
            if (s.matches("\\d{8}")) return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE).format(IT);
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) return s.replace('.', '/');
        } catch (RuntimeException e) {
            return s;
        }
        return s;
    }

    private static String sortable(String ddMMyyyy) {
        String[] p = ddMMyyyy.split("/");
        return p.length == 3 ? p[2] + p[1] + p[0] : ddMMyyyy;
    }

    private static String first(String a, String b) {
        return a != null && !a.isBlank() ? a : b == null ? "" : b;
    }

    private static String join(String sep, String... parts) {
        StringJoiner j = new StringJoiner(sep);
        for (String p : parts) if (p != null && !p.isBlank() && !p.equals("()")) j.add(p.trim());
        return j.toString();
    }
}
