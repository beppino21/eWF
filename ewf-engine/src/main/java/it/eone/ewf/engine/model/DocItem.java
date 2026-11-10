package it.eone.ewf.engine.model;

import java.math.BigDecimal;

/**
 * Posizione del documento fotografata all'avvio (OdA: EBELP; RdA: BNFPO).
 * Serve a capire se una modifica senza strategia di rilascio richiede di riapprovare:
 * il confronto e' per posizione, mai sui totali (variazioni opposte si annullerebbero).
 */
public record DocItem(String key, BigDecimal quantity, BigDecimal value) {}
