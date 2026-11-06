package it.eone.ewf.engine.spi;

import it.eone.ewf.engine.model.ProcessType;
import it.eone.ewf.engine.model.ProcessVersion;

import java.util.Optional;

public interface DefinitionStore {
    Optional<ProcessType> processType(String code);
    Optional<ProcessVersion> activeVersion(String processTypeCode);
    ProcessVersion version(long versionId);
}
