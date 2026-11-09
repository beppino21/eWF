-- eWF: migrazione schema 0.6 -> 0.7
-- Parametri dell'azione di fine processo (es. codice di rilascio OdA).
-- Eseguire come ewf_owner (o come postgres dopo SET ROLE ewf_owner).
BEGIN;
ALTER TABLE ewf_core.process_version
    ADD COLUMN end_handler_config jsonb NOT NULL DEFAULT '{}';
COMMIT;
