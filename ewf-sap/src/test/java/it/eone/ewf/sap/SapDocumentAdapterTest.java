package it.eone.ewf.sap;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Gli attributi per le regole devono essere quelli che leggeva WF_USER_TASK, non di piu'. */
class SapDocumentAdapterTest {

    private static final Map<String, String> SAP = Map.of(
            "WERKS", "D030", "BUKRS", "1000", "EKGRP", "Z01", "EKORG", "OA01", "LGORT", "0001", "LIFNR", "100200");

    @Test
    void odaSoloDivisioneESocieta() {
        Map<String, String> ctx = SapDocumentAdapter.ruleContext("F", SAP);
        assertEquals("D030", ctx.get("plant"));
        assertEquals("1000", ctx.get("company_code"));
        assertNull(ctx.get("purchasing_group"), "WF_USER_TASK non valorizza EKGRP per gli OdA");
        assertNull(ctx.get("storage_location"));
        assertEquals("Z01", ctx.get("sap.EKGRP"), "resta disponibile per audit e UI");
        assertNull(ctx.get("quantity"), "niente totali: le modifiche si confrontano per posizione");
    }

    @Test
    void rdaAncheGruppoAcquistiEMagazzino() {
        Map<String, String> ctx = SapDocumentAdapter.ruleContext("B", SAP);
        assertEquals("Z01", ctx.get("purchasing_group"));
        assertEquals("0001", ctx.get("storage_location"));
        assertEquals("D030", ctx.get("plant"));
    }
}
