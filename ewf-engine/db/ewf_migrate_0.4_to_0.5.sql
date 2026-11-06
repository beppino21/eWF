-- eWF: migrazione schema 0.4 -> 0.5
-- Stato MOVED per i task spostati (ex azione MOVE di WF_NEXT_STEP).
-- Eseguire come ewf_owner (o come postgres dopo SET ROLE ewf_owner).
BEGIN;
ALTER TABLE ewf_core.task_instance DROP CONSTRAINT task_instance_status_check;
ALTER TABLE ewf_core.task_instance ADD CONSTRAINT task_instance_status_check
    CHECK (status IN ('OPEN','EXECUTING','OK','KO','MOVED','CANCELLED','SKIPPED','ERROR'));
COMMIT;
