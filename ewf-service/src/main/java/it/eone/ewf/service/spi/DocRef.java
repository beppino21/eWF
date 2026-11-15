package it.eone.ewf.service.spi;

/** Documento nel sistema di origine: sistema (backend_code, puo' essere null = predefinito), tipo, chiave. */
public record DocRef(String backend, String docType, String documentKey) {}
