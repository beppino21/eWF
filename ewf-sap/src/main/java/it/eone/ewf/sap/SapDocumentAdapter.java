package it.eone.ewf.sap;

import com.sap.conn.jco.*;
import it.eone.ewf.engine.assign.RuleBasedAssignmentResolver;
import it.eone.ewf.engine.model.DocItem;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.spi.DocumentAdapter;
import it.eone.ewf.engine.spi.DocumentAdapterRegistry;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adapter documento per SAP on-premise: chiama /EWF/DOC_INFO via RFC (lettura diretta, senza dispatcher:
 * non modifica nulla, non serve idempotenza). La FM riusa /EWF/WF_DOCVALUE e /EWF/WF_BUKRS_WERKS, quindi
 * i tipi documento gia' personalizzati presso i clienti funzionano senza modifiche.
 *
 * Attributi per le regole: come il vecchio WF_USER_TASK.
 *  - OdA e contratti (F, K): divisione (prima posizione non cancellata) e societa';
 *  - RdA (B): divisione, gruppo acquisti, magazzino, societa'.
 * Tutti gli attributi restituiti da SAP restano comunque nel contesto con prefisso "sap." (audit, UI).
 */
public final class SapDocumentAdapter implements DocumentAdapter {

    public static final String FUNCTION = "/EWF/DOC_INFO";

    private static final Set<String> PO_RULE_FIELDS = Set.of("WERKS", "BUKRS");
    private static final Set<String> PR_RULE_FIELDS = Set.of("WERKS", "BUKRS", "EKGRP", "LGORT");

    private final String destinationName;
    private final String docType;

    public SapDocumentAdapter(String destinationName, String docType) {
        this.destinationName = destinationName;
        this.docType = docType;
    }

    /** Un adapter per ogni tipo documento, tutti sulla stessa FM. */
    public static DocumentAdapterRegistry registry(String destinationName) {
        return docType -> Optional.of(new SapDocumentAdapter(destinationName, docType));
    }

    @Override
    public DocumentInfo read(String documentKey) {
        JCoFunction f;
        JCoDestination dest;
        try {
            dest = JCoDestinationManager.getDestination(destinationName);
            f = dest.getRepository().getFunction(FUNCTION);
        } catch (JCoException e) {
            throw new UnavailableException("SAP non raggiungibile (" + destinationName + "): " + e.getMessage(), e);
        }
        if (f == null) throw new UnavailableException(FUNCTION + " non trovata nel sistema " + destinationName, null);

        f.getImportParameterList().setValue("I_TIPODOC", docType);
        f.getImportParameterList().setValue("I_DOCUMENT", documentKey);
        try {
            f.execute(dest);
        } catch (JCoException e) {
            throw new UnavailableException(FUNCTION + ": " + e.getMessage(), e);
        }

        JCoParameterList out = f.getExportParameterList();
        String message = blankToNull(out.getString("E_MESSAGE"));
        if (!"X".equals(out.getString("E_EXISTS"))) return DocumentInfo.notFound(message);

        Map<String, String> sap = new LinkedHashMap<>();
        JCoTable t = f.getTableParameterList().getTable("ET_CONTEXT");
        for (int i = 0; i < t.getNumRows(); i++) {
            t.setRow(i);
            String name = blankToNull(t.getString("NAME"));
            String value = blankToNull(t.getString("VALUE"));
            if (name != null && value != null) sap.put(name, value);
        }

        List<DocItem> items = new ArrayList<>();
        JCoTable it = f.getTableParameterList().getTable("ET_ITEMS");
        for (int i = 0; i < it.getNumRows(); i++) {
            it.setRow(i);
            items.add(new DocItem(it.getString("ITEM").trim(), it.getBigDecimal("MENGE"), it.getBigDecimal("NETWR")));
        }

        String waers = blankToNull(out.getString("E_WAERS"));
        BigDecimal netwr = out.getBigDecimal("E_NETWR");
        Money value = waers == null || netwr == null ? null : new Money(netwr, waers);
        return new DocumentInfo(true, value, ruleContext(docType, sap), message, items);
    }

    /** Traduce i campi SAP nelle chiavi del risolutore regole, secondo quello che leggeva WF_USER_TASK. */
    static Map<String, String> ruleContext(String docType, Map<String, String> sap) {
        Set<String> used = "B".equals(docType) ? PR_RULE_FIELDS
                : "F".equals(docType) || "K".equals(docType) ? PO_RULE_FIELDS : Set.of("WERKS", "BUKRS");
        Map<String, String> ctx = new LinkedHashMap<>();
        if (used.contains("WERKS") && sap.containsKey("WERKS")) ctx.put(RuleBasedAssignmentResolver.PLANT, sap.get("WERKS"));
        if (used.contains("EKGRP") && sap.containsKey("EKGRP"))
            ctx.put(RuleBasedAssignmentResolver.PURCHASING_GROUP, sap.get("EKGRP"));
        if (used.contains("LGORT") && sap.containsKey("LGORT"))
            ctx.put(RuleBasedAssignmentResolver.STORAGE_LOCATION, sap.get("LGORT"));
        if (used.contains("BUKRS") && sap.containsKey("BUKRS")) ctx.put("company_code", sap.get("BUKRS"));
        sap.forEach((k, v) -> ctx.put("sap." + k, v));
        return ctx;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
