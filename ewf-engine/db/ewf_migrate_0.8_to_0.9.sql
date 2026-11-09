-- eWF: migrazione schema 0.8 -> 0.9
-- Contesto del documento letto all'avvio (gruppo acquisti, divisione, ...): lo usano le regole di assegnazione.
-- Eseguire come ewf_owner. Modifica "in place": i permessi di ewf_app restano validi.
BEGIN;
ALTER TABLE ewf_core.process_instance ADD COLUMN doc_context jsonb NOT NULL DEFAULT '{}';
COMMIT;
