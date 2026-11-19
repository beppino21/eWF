package it.eone.ewf.sap.attach;

import it.eone.ewf.sap.sheet.Rfc;
import it.eone.ewf.service.spi.DocRef;
import it.eone.ewf.service.spi.DocumentAttachments;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SapDocumentAttachmentsTest {

    /** Rfc finto: registra le chiamate e risponde con i dati preparati. */
    static final class FakeRfc implements Rfc {
        final List<String> calls = new ArrayList<>();
        final List<Map<String, String>> imports = new ArrayList<>();
        List<Map<String, String>> rows = List.of();
        String error = "", message = "";
        byte[] content = new byte[0];

        List<Map<String, String>> sentTable = List.of();
        boolean missing;

        @Override
        public Result call(String function, Map<String, String> imp, Map<String, List<Map<String, String>>> tables) {
            if (missing) throw new FunctionMissing(function);
            sentTable = tables.getOrDefault("CT_DOCS", List.of());
            List<Map<String, String>> back = new ArrayList<>();
            for (Map<String, String> r : sentTable) {
                Map<String, String> m = new java.util.HashMap<>(r);
                m.put("ATT_COUNT", r.get("OBJKEY").endsWith("77") ? "2" : "0");
                back.add(m);
            }
            calls.add(function);
            return new Result() {
                public Map<String, String> structure(String n) { return Map.of(); }
                public List<Map<String, String>> table(String n) { return back; }
            };
        }

        @Override
        public Result call(String function, Map<String, String> imp) {
            calls.add(function);
            imports.add(imp);
            return new Result() {
                public Map<String, String> structure(String n) { return Map.of(); }
                public List<Map<String, String>> table(String n) { return "ET_ATTACH".equals(n) ? rows : List.of(); }
                public String value(String n) { return "E_ERROR".equals(n) ? error : "E_MESSAGE".equals(n) ? message : ""; }
                public byte[] bytes(String n) { return "E_CONTENT".equals(n) ? content : new byte[0]; }
            };
        }
    }

    static final DocRef PO = new DocRef(null, "F", "4500000123");
    static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @Test
    void listMapsRowsAndPadsKey() {
        FakeRfc rfc = new FakeRfc();
        rfc.rows = List.of(
                Map.of("ATT_ID", "GFOL123", "FILENAME", "Offerta.pdf", "MIMETYPE", "application/pdf",
                        "FILESIZE", "1234", "CREATED_BY", "MROSSI", "CREATED_NAME", "Mario Rossi",
                        "CREATED_ON", "20261118", "CREATED_AT", "143000"),
                Map.of("ATT_ID", "AZ1DOC", "FILENAME", "", "MIMETYPE", "", "FILESIZE", "0",
                        "CREATED_BY", "BUYER1", "CREATED_NAME", "", "CREATED_ON", "00000000", "CREATED_AT", "000000"),
                Map.of("ATT_ID", "", "FILENAME", "scartato"));
        SapDocumentAttachments a = new SapDocumentAttachments(rfc, SapDocumentAttachments.DEFAULT_OBJECT_TYPES, ROME);
        List<DocumentAttachments.Item> items = a.list(new DocRef(null, "F", "123"));

        assertEquals(List.of(SapDocumentAttachments.FM_LIST), rfc.calls);
        assertEquals("BUS2012", rfc.imports.get(0).get("I_OBJTYPE"));
        assertEquals("0000000123", rfc.imports.get(0).get("I_OBJKEY"));
        assertEquals(2, items.size());
        DocumentAttachments.Item i = items.get(0);
        assertEquals("GFOL123", i.id());
        assertEquals("Offerta.pdf", i.fileName());
        assertEquals("application/pdf", i.mimeType());
        assertEquals(1234L, i.sizeBytes());
        assertEquals("Mario Rossi", i.createdBy());
        assertEquals(Instant.parse("2026-11-18T13:30:00Z"), i.createdAt());
        DocumentAttachments.Item j = items.get(1);
        assertEquals("allegato", j.fileName());
        assertNull(j.mimeType());
        assertNull(j.sizeBytes());
        assertEquals("BUYER1", j.createdBy());
        assertNull(j.createdAt());
    }

    @Test
    void countsInOneCall() {
        FakeRfc rfc = new FakeRfc();
        SapDocumentAttachments a = new SapDocumentAttachments(rfc, SapDocumentAttachments.DEFAULT_OBJECT_TYPES, ROME);
        DocRef d77 = new DocRef(null, "F", "4500001077"), d68 = new DocRef(null, "F", "4500001068");
        Map<DocRef, Integer> c = a.counts(List.of(d77, d68, d77, new DocRef(null, "B", "1")));
        assertEquals(List.of(SapDocumentAttachments.FM_COUNT), rfc.calls, "una sola chiamata");
        assertEquals(2, rfc.sentTable.size(), "doppioni e tipi non previsti esclusi");
        assertEquals(2, c.get(d77));
        assertEquals(0, c.get(d68));
        rfc.missing = true;
        assertTrue(a.counts(List.of(d77)).isEmpty(), "FM non ancora installata: nessun conteggio");
    }

    @Test
    void jcoDateFormatsAreBothAccepted() {
        SapDocumentAttachments a = new SapDocumentAttachments(new FakeRfc(), SapDocumentAttachments.DEFAULT_OBJECT_TYPES, ROME);
        Instant expected = Instant.parse("2026-11-18T13:30:00Z");
        assertEquals(expected, a.instant("2026-11-18", "14:30:00"));
        assertEquals(expected, a.instant("20261118", "143000"));
        assertNull(a.instant("0000-00-00", "00:00:00"));
        assertEquals(Instant.parse("2026-11-17T23:00:00Z"), a.instant("2026-11-18", ""));
    }

    @Test
    void readPassesIdAndReturnsBytes() {
        FakeRfc rfc = new FakeRfc();
        rfc.content = new byte[]{1, 2, 3};
        SapDocumentAttachments a = new SapDocumentAttachments(rfc, SapDocumentAttachments.DEFAULT_OBJECT_TYPES, ROME);
        assertArrayEquals(new byte[]{1, 2, 3}, a.read(PO, "GFOL123"));
        assertEquals(SapDocumentAttachments.FM_GET, rfc.calls.get(0));
        assertEquals("GFOL123", rfc.imports.get(0).get("I_ATT_ID"));
        assertEquals("4500000123", rfc.imports.get(0).get("I_OBJKEY"));
    }

    @Test
    void sapErrorBecomesException() {
        FakeRfc rfc = new FakeRfc();
        rfc.error = "X";
        rfc.message = "Allegato non collegato al documento";
        SapDocumentAttachments a = new SapDocumentAttachments(rfc, SapDocumentAttachments.DEFAULT_OBJECT_TYPES, ROME);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.read(PO, "GX"));
        assertTrue(e.getMessage().contains("non collegato"));
    }

    @Test
    void supportsOnlyMappedTypesAndIsReadOnly() {
        SapDocumentAttachments a = new SapDocumentAttachments(new FakeRfc(), SapDocumentAttachments.DEFAULT_OBJECT_TYPES, ROME);
        assertTrue(a.supports(PO));
        assertFalse(a.supports(new DocRef(null, "B", "10000001")));
        assertFalse(a.supports(new DocRef(null, "F", " ")));
        assertFalse(a.canAdd(PO));
        assertThrows(UnsupportedOperationException.class, () -> a.add(PO, "x.pdf", "application/pdf", new byte[]{1}, null, null));
    }
}
