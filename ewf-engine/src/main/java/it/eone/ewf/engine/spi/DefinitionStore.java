package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.model.StartRule;

import java.util.Optional;

public interface DefinitionStore {
    Optional<ProcessType> processType(String code);
    Optional<ProcessVersion> activeVersion(String processTypeCode);
    ProcessVersion version(long versionId);

    /** Regole di avvio per il tipo documento (vuoto = nessun avvio automatico). */
    default java.util.List<StartRule> startRules(String docType) { return java.util.List.of(); }
}
