package it.eone.ewf.sap.sheet;

import it.eone.ewf.service.sheet.DocumentSheet;
import it.eone.ewf.service.spi.DocRef;
import it.eone.ewf.service.spi.DocumentSheets;

import java.util.List;
import java.util.Map;

/**
 * Schede documento da SAP on-premise (R/3, S/4HANA on-premise e Private) via RFC.
 * Per ora: OdA (tipo documento F) -> "Scheda OdA". Gli altri tipi hanno il solo riepilogo eWF.
 */
public final class SapDocumentSheets implements DocumentSheets {

    public static final String PO_VIEW = "ODA";

    private final Rfc rfc;

    public SapDocumentSheets(Rfc rfc) {
        this.rfc = rfc;
    }

    public static SapDocumentSheets forDestination(String destinationName) {
        return new SapDocumentSheets(new JcoRfc(destinationName));
    }

    @Override
    public List<View> views(DocRef doc) {
        return "F".equals(doc.docType()) ? List.of(new View(PO_VIEW, "Scheda OdA")) : List.of();
    }

    @Override
    public DocumentSheet build(DocRef doc, String viewCode, Map<String, String> context) {
        if ("F".equals(doc.docType()) && PO_VIEW.equals(viewCode)) return new PoSheet(rfc).build(doc.documentKey(), context);
        throw new IllegalArgumentException("Vista " + viewCode + " non prevista per " + doc.docType());
    }
}
