package it.eone.ewf.engine.model;

/**
 * Avviso al destinatario quando un task gli viene assegnato.
 * NONE (default): nessuna mail; il task compare nel riepilogo periodico dell'utente (mail_every_days).
 * IMMEDIATE: mail subito. Da usare solo dove serve davvero: troppe mail fanno ignorare tutte le mail.
 */
public enum NotifyMode { NONE, IMMEDIATE }
