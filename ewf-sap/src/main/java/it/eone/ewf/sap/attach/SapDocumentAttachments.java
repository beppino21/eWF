package it.eone.ewf.sap.attach;

import it.eone.ewf.sap.sheet.JcoRfc;
import it.eone.ewf.sap.sheet.Rfc;
import it.eone.ewf.service.spi.DocRef;
import it.eone.ewf.service.spi.DocumentAttachments;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allegati del documento in SAP (R/3, S/4HANA on-premise e Private) via RFC: allegati GOS e documenti
 * ArchiveLink collegati all'oggetto, letti da /EWF/DOC_ATTACH_LIST e scaricati con /EWF/DOC_ATTACH_GET.
 * Per ora in sola consultazione: {@link #canAdd} e' falso.
 * <p>
 * L'id dell'allegato e' quello restituito da SAP ("G..." per GOS, "A..." per ArchiveLink): eWF lo tratta come
 * opaco e la FM di lettura verifica a sua volta che appartenga al documento.
 */
public final class SapDocumentAttachments implements DocumentAttachments {

    public static final String FM_LIST = "/EWF/DOC_ATTACH_LIST";
    public static final String FM_GET = "/EWF/DOC_ATTACH_GET";
    public static final String FM_COUNT = "/EWF/DOC_ATTACH_COUNT";

    /** Tipo documento eWF -> tipo oggetto SAP (BOR) a cui sono collegati gli allegati. */
    public static final Map<String, String> DEFAULT_OBJECT_TYPES = Map.of("F", "BUS2012");

    private final Rfc rfc;
    private final Map<String, String> objectTypes;
    private final ZoneId sapZone;

    public SapDocumentAttachments(Rfc rfc, Map<String, String> objectTypes, ZoneId sapZone) {
        this.rfc = rfc;
        this.objectTypes = new HashMap<>(objectTypes);
        this.sapZone = sapZone;
    }

    public static SapDocumentAttachments forDestination(String destinationName) {
        return new SapDocumentAttachments(new JcoRfc(destinationName), DEFAULT_OBJECT_TYPES, ZoneId.systemDefault());
    }

    @Override
    public boolean supports(DocRef doc) {
        return doc != null && objectTypes.containsKey(doc.docType()) && doc.documentKey() != null
                && !doc.documentKey().isBlank();
    }

    @Override
    public boolean canAdd(DocRef doc) {
        return false;
    }

    @Override
    public List<Item> list(DocRef doc) {
        Rfc.Result r = call(FM_LIST, doc, Map.of());
        List<Item> out = new ArrayList<>();
        for (Map<String, String> row : r.table("ET_ATTACH")) {
            String id = row.getOrDefault("ATT_ID", "");
            if (id.isEmpty()) continue;
            String by = row.getOrDefault("CREATED_NAME", "");
            if (by.isEmpty()) by = row.getOrDefault("CREATED_BY", "");
            out.add(new Item(id, fileName(row), emptyToNull(row.get("MIMETYPE")), size(row.get("FILESIZE")),
                    emptyToNull(by), instant(row.get("CREATED_ON"), row.get("CREATED_AT"))));
        }
        return out;
    }

    /** Una sola chiamata per tutti i documenti della pagina. Se la FM non c'e' ancora: nessun conteggio. */
    @Override
    public Map<DocRef, Integer> counts(Collection<DocRef> docs) {
        Map<String, DocRef> byKey = new LinkedHashMap<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (DocRef d : docs) {
            if (!supports(d)) continue;
            String type = objectTypes.get(d.docType()), key = objectKey(d);
            if (byKey.putIfAbsent(type + "|" + key, d) == null) rows.add(Map.of("OBJTYPE", type, "OBJKEY", key));
        }
        if (rows.isEmpty()) return Map.of();
        Rfc.Result r;
        try {
            r = rfc.call(FM_COUNT, Map.of(), Map.of("CT_DOCS", rows));
        } catch (Rfc.FunctionMissing e) {
            return Map.of();
        }
        Map<DocRef, Integer> out = new HashMap<>();
        for (Map<String, String> row : r.table("CT_DOCS")) {
            DocRef d = byKey.get(row.getOrDefault("OBJTYPE", "") + "|" + row.getOrDefault("OBJKEY", ""));
            Long n = size(row.get("ATT_COUNT"));
            if (d != null) out.put(d, n == null ? 0 : n.intValue());
        }
        return out;
    }

    @Override
    public byte[] read(DocRef doc, String id) {
        return call(FM_GET, doc, Map.of("I_ATT_ID", id)).bytes("E_CONTENT");
    }

    @Override
    public String add(DocRef doc, String fileName, String mimeType, byte[] content, String sapUser, String uploaderName) {
        throw new UnsupportedOperationException("Caricamento degli allegati in SAP non ancora previsto");
    }

    private Rfc.Result call(String fm, DocRef doc, Map<String, String> extra) {
        if (!supports(doc)) throw new IllegalArgumentException("Allegati SAP non previsti per " + doc);
        Map<String, String> imp = new HashMap<>(extra);
        imp.put("I_OBJTYPE", objectTypes.get(doc.docType()));
        imp.put("I_OBJKEY", objectKey(doc));
        Rfc.Result r = rfc.call(fm, imp);
        if ("X".equals(r.value("E_ERROR"))) {
            String m = r.value("E_MESSAGE");
            throw new IllegalStateException(fm + ": " + (m.isEmpty() ? "errore in SAP" : m));
        }
        return r;
    }

    /** Chiave dell'oggetto SAP: per l'OdA il numero con gli zeri iniziali (EBELN, 10). */
    static String objectKey(DocRef doc) {
        String k = doc.documentKey().trim();
        if ("F".equals(doc.docType()) && k.matches("\\d{1,10}")) return "0".repeat(10 - k.length()) + k;
        return k;
    }

    static String fileName(Map<String, String> row) {
        String n = row.getOrDefault("FILENAME", "");
        return n.isEmpty() ? "allegato" : n;
    }

    static Long size(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** DATS/TIMS arrivano da JCo come "2026-11-19" / "14:30:00" (getString) oppure "20261119" / "143000". */
    Instant instant(String date, String time) {
        String d = date == null ? "" : date.replace("-", "").trim();
        if (!d.matches("\\d{8}") || d.equals("00000000")) return null;
        String t = time == null ? "" : time.replace(":", "").trim();
        try {
            LocalDate ld = LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE);
            LocalTime lt = t.matches("\\d{6}") ? LocalTime.parse(t, DateTimeFormatter.ofPattern("HHmmss")) : LocalTime.MIDNIGHT;
            return ld.atTime(lt).atZone(sapZone).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
