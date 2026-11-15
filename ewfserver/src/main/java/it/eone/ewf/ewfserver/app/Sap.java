package it.eone.ewf.ewfserver.app;

import java.util.Optional;

import it.eone.ewf.engine.spi.DocumentAdapterRegistry;
import it.eone.ewf.engine.spi.HandlerRegistry;
import it.eone.ewf.sap.SapDestinations;
import it.eone.ewf.sap.SapDocumentAdapter;
import it.eone.ewf.sap.SapHandlerRegistry;
import it.eone.ewf.sap.sheet.SapDocumentSheets;
import it.eone.ewf.service.EwfService;

/**
 * Collegamento a SAP via JCo (ewf-sap). Se JCo non e' installato in Tomcat o la destinazione non e' valida,
 * l'applicazione parte lo stesso senza SAP: lista e dettaglio funzionano, mancano schede SAP e azioni verso SAP.
 */
record Sap(String destination, HandlerRegistry handlers, DocumentAdapterRegistry adapters, EwfService.Backends backends) {

    static Sap none() {
        return new Sap(null, code -> Optional.empty(), docType -> Optional.empty(), EwfService.Backends.none());
    }

    static Sap connect(String dest) {
        try {
            SapDestinations.register();
            return new Sap(dest, new SapHandlerRegistry(dest), SapDocumentAdapter.registry(dest),
                    new EwfService.Backends(null, SapDocumentSheets.forDestination(dest), null));
        } catch (LinkageError | RuntimeException e) {
            System.err.println("[EwfApp] SAP " + dest + " non disponibile (" + e + "): avvio senza SAP."
                    + " JCo (sapjco3.jar e libreria nativa) va nella lib di Tomcat.");
            return none();
        }
    }
}
