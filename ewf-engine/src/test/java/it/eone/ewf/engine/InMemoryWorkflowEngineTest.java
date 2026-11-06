package it.eone.ewf.engine;

/** Scenari del motore con le implementazioni in memoria. */
class InMemoryWorkflowEngineTest extends WorkflowEngineContract {
    @Override
    protected Backend newBackend() {
        return new MemoryBackend();
    }
}
