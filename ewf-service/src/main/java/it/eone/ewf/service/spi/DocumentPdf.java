package it.eone.ewf.service.spi;

/**
 * Stampa del documento in PDF, come la vedrebbe il fornitore o il cliente (ex /EWF/WF_VIEWMODULO).
 * Su SAP: /EWF/DOC_PDF (tipo + numero -> PDF via spool, senza dialogo).
 */
public interface DocumentPdf {

    boolean supports(DocRef doc);

    /** PDF completo; eccezione non controllata se il sistema di origine non risponde o non sa stampare. */
    byte[] render(DocRef doc);
}
