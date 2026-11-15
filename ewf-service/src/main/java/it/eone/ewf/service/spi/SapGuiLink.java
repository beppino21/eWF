package it.eone.ewf.service.spi;

/**
 * "Visualizza registrazione gestionale" (ex /EWF/WF_VIEWDOC): collegamento SAP GUI che apre il documento.
 * Su SAP: file .sap che lancia la transazione /EWF/VIEWDOC con tipo e numero. Solo per utenti con utente SAP.
 */
public interface SapGuiLink {

    /** File da scaricare: nome, contenuto, tipo MIME (per il .sap: application/x-sapshortcut). */
    record Shortcut(String fileName, byte[] content, String mimeType) {}

    boolean supports(DocRef doc);

    Shortcut shortcut(DocRef doc, String sapUser);
}
