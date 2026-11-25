package it.eone.ewf.s4cloud;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API_PURCHASEORDER_2 (OData V4, scenario SAP_COM_0053): solo lettura.
 * Nel tenant non esiste un'azione di rilascio o di rifiuto: lo stato si osserva, non si cambia (D23).
 */
public final class PurchaseOrderApi {

    static final String SERVICE = "/sap/opu/odata4/sap/api_purchaseorder_2/srvd_a2x/sap/purchaseorder/0001/";

    private final S4Connection s4;

    public PurchaseOrderApi(S4Connection s4) { this.s4 = s4; }

    public S4Connection connection() { return s4; }

    /** Un OdA con le sue posizioni; vuoto se non esiste. */
    public Optional<PurchaseOrder> read(String number) {
        String path = SERVICE + "PurchaseOrder('" + number.replace("'", "''") + "')"
                + "?$select=" + enc(PurchaseOrder.SELECT) + "&$expand=" + enc(PurchaseOrder.EXPAND);
        try {
            return Optional.of(PurchaseOrder.fromJson(s4.getJson(path)));
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof S4Connection.NotFound) return Optional.empty();
            throw e;
        }
    }

    /**
     * OdA modificati dopo "after" (escluso), dal piu' vecchio, al massimo "top".
     * LastChangeDateTime e' in UTC con i microsecondi: nella V4 il letterale va senza apici.
     */
    @SuppressWarnings("unchecked")
    public List<PurchaseOrder> changedAfter(Instant after, int top) {
        String path = SERVICE + "PurchaseOrder"
                + "?$filter=" + enc("LastChangeDateTime gt " + after)
                + "&$orderby=" + enc("LastChangeDateTime asc,PurchaseOrder asc")
                + "&$top=" + top
                + "&$select=" + enc(PurchaseOrder.SELECT) + "&$expand=" + enc(PurchaseOrder.EXPAND);
        Map<String, Object> r = s4.getJson(path);
        List<PurchaseOrder> out = new ArrayList<>();
        if (r.get("value") instanceof List<?> l)
            for (Object o : l) out.add(PurchaseOrder.fromJson((Map<String, Object>) o));
        return out;
    }

    /** Spazi come %20 (non '+'): alcuni gateway non accettano '+' nella query OData. */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
