package it.eone.ewf.s4cloud;

import it.eone.ewf.engine.assign.RuleBasedAssignmentResolver;
import it.eone.ewf.engine.model.DocItem;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.DocumentAdapter.DocumentInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fotografia di un OdA letta da API_PURCHASEORDER_2 (OData V4).
 *
 * Stato (PurchasingProcessingStatus), come verificato sul tenant di sviluppo (D23):
 *   03 = in approvazione (Flexible Workflow avviato), 05 = rilasciato, 08 = respinto.
 * Il valore e' la somma del NetAmount delle posizioni non cancellate: la testata non ha un totale.
 */
public record PurchaseOrder(String number, String type, String status, boolean releaseNotCompleted,
                            String deletionCode, Instant lastChange, String createdBy,
                            String companyCode, String purchasingOrganization, String purchasingGroup,
                            String supplier, String currency, List<Item> items) {

    public static final String IN_APPROVAL = "03", RELEASED = "05", REJECTED = "08";
    /** Attributo del contesto con il tipo di sistema (backend_system.kind). */
    public static final String BACKEND_KIND = "backend_kind";

    public record Item(String item, String text, String material, String materialGroup, String plant,
                       BigDecimal quantity, String unit, BigDecimal netAmount, String deletionCode) {
        public boolean deleted() { return deletionCode != null && !deletionCode.isBlank(); }
    }

    public PurchaseOrder {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public List<Item> activeItems() {
        return items.stream().filter(i -> !i.deleted()).toList();
    }

    /** Cancellato in testata, o senza posizioni attive. */
    public boolean deleted() {
        return (deletionCode != null && !deletionCode.isBlank()) || activeItems().isEmpty();
    }

    public Money value() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Item i : activeItems()) if (i.netAmount() != null) sum = sum.add(i.netAmount());
        return currency == null || currency.isBlank() ? null : new Money(sum, currency);
    }

    /**
     * Attributi per le regole di avvio e di assegnazione. Stesse chiavi dell'adattatore on-premise
     * (plant, purchasing_group, company_code e i campi sap.* con i nomi delle tabelle classiche), cosi' le regole
     * scritte per un sistema valgono anche per l'altro. Divisione: quella della prima posizione attiva.
     * In piu' backend_kind = S4_PUBLIC, per scrivere una regola di avvio valida solo per S/4 Public.
     */
    public Map<String, String> context() {
        Map<String, String> c = new LinkedHashMap<>();
        // per le regole di avvio: distingue gli OdA di S/4 Public da quelli di un SAP on-premise (stesso tipo "F")
        c.put(BACKEND_KIND, "S4_PUBLIC");
        String plant = activeItems().stream().map(Item::plant).filter(p -> p != null && !p.isBlank()).findFirst().orElse(null);
        put(c, RuleBasedAssignmentResolver.PLANT, plant);
        put(c, RuleBasedAssignmentResolver.PURCHASING_GROUP, purchasingGroup);
        put(c, "company_code", companyCode);
        put(c, "sap.BSART", type);
        put(c, "sap.EKORG", purchasingOrganization);
        put(c, "sap.EKGRP", purchasingGroup);
        put(c, "sap.BUKRS", companyCode);
        put(c, "sap.LIFNR", supplier);
        put(c, "sap.WERKS", plant);
        put(c, "sap.ERNAM", createdBy);
        return c;
    }

    public DocumentInfo toDocumentInfo() {
        if (deleted())
            return new DocumentInfo(false, null, context(),
                    deletionCode != null && !deletionCode.isBlank() ? "OdA cancellato" : "OdA senza posizioni attive", List.of());
        List<DocItem> di = new ArrayList<>();
        for (Item i : activeItems()) di.add(new DocItem(i.item(), i.quantity(), i.netAmount()));
        return new DocumentInfo(true, value(), context(), null, di);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v.trim());
    }

    // ------------------------------------------------------------------
    // Lettura dal JSON di API_PURCHASEORDER_2
    // ------------------------------------------------------------------

    /** Campi chiesti all'API (stessi in lettura singola e nel polling). */
    static final String SELECT = "PurchaseOrder,PurchaseOrderType,PurchasingProcessingStatus,ReleaseIsNotCompleted,"
            + "PurchaseOrderDeletionCode,LastChangeDateTime,CreatedByUser,CompanyCode,PurchasingOrganization,"
            + "PurchasingGroup,Supplier,DocumentCurrency";
    static final String EXPAND = "_PurchaseOrderItem($select=PurchaseOrderItem,PurchaseOrderItemText,Material,"
            + "MaterialGroup,Plant,OrderQuantity,PurchaseOrderQuantityUnit,NetAmount,PurchasingDocumentDeletionCode)";

    @SuppressWarnings("unchecked")
    static PurchaseOrder fromJson(Map<String, Object> o) {
        List<Item> items = new ArrayList<>();
        Object list = o.get("_PurchaseOrderItem");
        if (list instanceof List<?> l)
            for (Object x : l) {
                Map<String, Object> m = (Map<String, Object>) x;
                items.add(new Item(str(m, "PurchaseOrderItem"), str(m, "PurchaseOrderItemText"), str(m, "Material"),
                        str(m, "MaterialGroup"), str(m, "Plant"), dec(m, "OrderQuantity"),
                        str(m, "PurchaseOrderQuantityUnit"), dec(m, "NetAmount"),
                        str(m, "PurchasingDocumentDeletionCode")));
            }
        String ts = str(o, "LastChangeDateTime");
        return new PurchaseOrder(str(o, "PurchaseOrder"), str(o, "PurchaseOrderType"), str(o, "PurchasingProcessingStatus"),
                Boolean.TRUE.equals(o.get("ReleaseIsNotCompleted")), str(o, "PurchaseOrderDeletionCode"),
                ts == null ? null : Instant.parse(ts), str(o, "CreatedByUser"), str(o, "CompanyCode"),
                str(o, "PurchasingOrganization"), str(o, "PurchasingGroup"), str(o, "Supplier"),
                str(o, "DocumentCurrency"), items);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static BigDecimal dec(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof BigDecimal b ? b : v == null ? null : new BigDecimal(v.toString());
    }
}
