-- eWF: migrazione schema 0.9 -> 0.10
-- Opzioni che avviano un processo collegato (passaggio di consegne / parallelo) e stato FORWARDED.
-- Eseguire come ewf_owner. Modifiche "in place": i permessi di ewf_app restano validi.
BEGIN;

ALTER TABLE ewf_core.task_option
    ADD COLUMN start_process_type varchar(20),
    ADD COLUMN spawn_mode         varchar(10) CHECK (spawn_mode IN ('HANDOFF','PARALLEL'));

ALTER TABLE ewf_core.process_instance DROP CONSTRAINT process_instance_status_check;
ALTER TABLE ewf_core.process_instance ADD CONSTRAINT process_instance_status_check
    CHECK (status IN ('RUNNING','SUSPENDED','COMPLETING','COMPLETED','REJECTED','CANCELLED','ERROR','FORWARDED'));

COMMIT;
