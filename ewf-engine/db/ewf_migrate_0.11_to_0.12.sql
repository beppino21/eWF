-- eWF: migrazione schema 0.11 -> 0.12
-- Posizioni del documento fotografate all'avvio: le modifiche senza strategia di rilascio
-- si confrontano posizione per posizione (mai sui totali).
-- Eseguire come ewf_owner. Modifica "in place": i permessi di ewf_app restano validi.
BEGIN;
ALTER TABLE ewf_core.process_instance ADD COLUMN doc_items jsonb NOT NULL DEFAULT '{}';
COMMIT;
