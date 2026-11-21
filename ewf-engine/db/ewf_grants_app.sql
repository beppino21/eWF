-- eWF: permessi dell'utente applicativo ewf_app (schema 0.16).
--
-- Script UNICO e COMPLETO: si esegue come ewf_owner (proprietario delle tabelle) in ogni ambiente,
-- dopo l'installazione o dopo una migrazione. Si puo' rieseguire quante volte si vuole.
-- Lo schema eone_connect ha i suoi permessi: li assegna ConnectSchemaTool (terzo argomento ewf_app).
--
-- Principio: ewf_app legge tutto, scrive solo cio' che l'applicazione scrive davvero.
-- Le definizioni dei processi le modifica il designer, ma solo nelle bozze: lo garantiscono anche i trigger
-- guard_definition e guard_process_version (schema 0.16).

BEGIN;

REVOKE ALL ON ALL TABLES IN SCHEMA ewf_core, ewf_org FROM ewf_app;

GRANT USAGE ON SCHEMA ewf_core, ewf_org TO ewf_app;

-- Lettura: tutto
GRANT SELECT ON ALL TABLES IN SCHEMA ewf_core, ewf_org TO ewf_app;

-- Motore: processi e task in corso
GRANT INSERT, UPDATE ON ewf_core.process_instance, ewf_core.task_instance,
                        ewf_core.handler_execution, ewf_core.notification TO ewf_app;
GRANT INSERT, DELETE ON ewf_core.instance_step_state TO ewf_app;
GRANT INSERT ON ewf_core.generated_document, ewf_core.process_link TO ewf_app;

-- Audit: solo aggiunte (un trigger blocca comunque UPDATE e DELETE)
GRANT INSERT ON ewf_core.audit_event TO ewf_app;

-- Commenti e allegati: aggiunte; degli allegati solo la cancellazione logica
GRANT INSERT ON ewf_core.comment, ewf_core.attachment, ewf_core.attachment_content TO ewf_app;
GRANT UPDATE (deleted_at, deleted_by) ON ewf_core.attachment TO ewf_app;

-- Designer dei processi: versioni in bozza, step, task, agganci, opzioni, funzioni
GRANT INSERT, DELETE ON ewf_core.process_version TO ewf_app;
GRANT UPDATE (status, activated_at, note, end_handler_id, end_handler_config) ON ewf_core.process_version TO ewf_app;
GRANT INSERT, UPDATE, DELETE ON ewf_core.step_def, ewf_core.task_def, ewf_core.task_hook,
                                ewf_core.task_option, ewf_core.task_function TO ewf_app;

-- Regole di avvio (gestibili dall'applicazione)
GRANT INSERT, UPDATE, DELETE ON ewf_core.start_rule TO ewf_app;

-- Utenti: campi dell'accesso e data dell'ultimo riepilogo; gestione utenti dall'amministratore
-- (creazione, anagrafica essenziale, classe, permesso di sostituzione, attivo/disattivo; il login non cambia)
GRANT INSERT ON ewf_org.app_user TO ewf_app;
GRANT UPDATE (password_hash, password_must_change, last_login_at, failed_logins, locked_until,
              last_mail_on, updated_at,
              display_name, email, sap_user, user_class_code, can_substitute, active) ON ewf_org.app_user TO ewf_app;

-- Team e appartenenze, gestiti dagli amministratori (il codice del team non cambia)
GRANT INSERT ON ewf_org.team TO ewf_app;
GRANT UPDATE (description) ON ewf_org.team TO ewf_app;
GRANT INSERT, DELETE ON ewf_org.team_member TO ewf_app;
GRANT UPDATE (valid_from, valid_to) ON ewf_org.team_member TO ewf_app;

-- Tipi processo e tipi documento creati dal designer (il codice non cambia; gli indicatori del legacy restano fuori)
GRANT INSERT ON ewf_core.doc_type, ewf_core.process_type TO ewf_app;
GRANT UPDATE (description) ON ewf_core.doc_type TO ewf_app;
GRANT UPDATE (description, manual_start, suspended, list_summary, list_columns, sla_yellow_days, sla_red_days,
              attachment_scope) ON ewf_core.process_type TO ewf_app;

-- Ruoli e regole di assegnazione, gestiti dagli amministratori (il codice del ruolo non cambia)
GRANT INSERT, DELETE ON ewf_org.role TO ewf_app;
GRANT UPDATE (description) ON ewf_org.role TO ewf_app;
GRANT INSERT, UPDATE, DELETE ON ewf_org.assignment_rule TO ewf_app;

-- Sostituzioni indicate dagli utenti stessi
GRANT INSERT, DELETE ON ewf_org.substitution TO ewf_app;
GRANT UPDATE (valid_to, changed_by, changed_at) ON ewf_org.substitution TO ewf_app;

-- Numeratori (identity) delle tabelle in cui l'applicazione inserisce
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ewf_core, ewf_org TO ewf_app;

COMMIT;
