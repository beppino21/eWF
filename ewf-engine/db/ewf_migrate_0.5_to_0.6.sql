-- eWF: migrazione schema 0.5 -> 0.6 (persistenza del motore Java)
-- Eseguire come ewf_owner (o come postgres dopo SET ROLE ewf_owner).
BEGIN;

-- motivo dello stato (es. END_FAILED: ..., UNKNOWN_EXECUTION:123, RESTARTED)
ALTER TABLE ewf_core.process_instance ADD COLUMN status_reason varchar(255);

-- l'esecuzione registra sempre il codice handler; l'FK al catalogo e' facoltativa
ALTER TABLE ewf_core.handler_execution ADD COLUMN handler_code varchar(60);
UPDATE ewf_core.handler_execution e SET handler_code = h.code
  FROM ewf_core.handler h WHERE h.id = e.handler_id;
ALTER TABLE ewf_core.handler_execution ALTER COLUMN handler_code SET NOT NULL;
ALTER TABLE ewf_core.handler_execution ALTER COLUMN handler_id DROP NOT NULL;

-- documenti contabili: esercizio e societa'
ALTER TABLE ewf_core.generated_document ADD COLUMN doc_year varchar(4);
ALTER TABLE ewf_core.generated_document ADD COLUMN company_code varchar(4);

-- 'BEFORE_ACTION' ha 13 caratteri: le colonne event erano varchar(12)
ALTER TABLE ewf_core.task_hook ALTER COLUMN event TYPE varchar(15);
ALTER TABLE ewf_core.handler_execution ALTER COLUMN event TYPE varchar(15);

-- codice team: da 2 a 20 caratteri (il team e' ora un'anagrafica, es. 'WFADM', 'ACQ_NORD')
ALTER TABLE ewf_org.team                 ALTER COLUMN code             TYPE varchar(20);
ALTER TABLE ewf_org.team_member          ALTER COLUMN team_code        TYPE varchar(20);
ALTER TABLE ewf_org.assignment_rule      ALTER COLUMN target_team_code TYPE varchar(20);
ALTER TABLE ewf_org.role_team_auth       ALTER COLUMN team_code        TYPE varchar(20);
ALTER TABLE ewf_core.task_def            ALTER COLUMN team_code        TYPE varchar(20);
ALTER TABLE ewf_core.task_instance       ALTER COLUMN team_code        TYPE varchar(20);

COMMIT;
