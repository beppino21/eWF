package it.eone.ewf.s4cloud;

import it.eone.ewf.engine.spi.DocumentAdapter;

/**
 * Adattatore del documento per gli OdA di S/4 Public (tipo documento "F", come on-premise):
 * valore, attributi per le regole e posizioni letti da API_PURCHASEORDER_2.
 */
public final class S4PurchaseOrderAdapter implements DocumentAdapter {

    public static final String DOC_TYPE = "F";

    private final PurchaseOrderApi api;

    public S4PurchaseOrderAdapter(PurchaseOrderApi api) { this.api = api; }

    @Override
    public DocumentInfo read(String documentKey) {
        try {
            return api.read(documentKey).map(PurchaseOrder::toDocumentInfo)
                    .orElseGet(() -> DocumentInfo.notFound("OdA " + documentKey + " inesistente in S/4 " + api.connection().name()));
        } catch (IllegalStateException e) {
            throw new UnavailableException(e.getMessage(), e);
        }
    }
}
