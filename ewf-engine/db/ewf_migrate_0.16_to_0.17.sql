-- eWF: migrazione schema 0.16 -> 0.17
-- Commento richiesto sulle azioni del task, impostabile task per task nel designer:
--   NONE     = campo non proposto
--   OPTIONAL = proposto, si puo' lasciare vuoto
--   REQUIRED = senza commento il motore rifiuta l'azione (vale per web, mobile e ogni altro canale)
-- Predefiniti: approvazione OPTIONAL, rifiuto REQUIRED. Valgono anche per le versioni gia' attive
-- (decisione del 23/11/2026): da subito, per respingere serve un commento.
-- Nei nodi di scelta l'opzione segue la regola dell'approvazione, o del rifiuto se l'opzione respinge.
-- Eseguire come ewf_owner (i trigger di guardia valgono solo per ewf_app). Nessun nuovo permesso:
-- ewf_app ha gia' INSERT/UPDATE su task_def.
BEGIN;

ALTER TABLE ewf_core.task_def
    ADD COLUMN comment_on_approve varchar(10) NOT NULL DEFAULT 'OPTIONAL'
        CHECK (comment_on_approve IN ('NONE','OPTIONAL','REQUIRED')),
    ADD COLUMN comment_on_reject  varchar(10) NOT NULL DEFAULT 'REQUIRED'
        CHECK (comment_on_reject IN ('NONE','OPTIONAL','REQUIRED'));

COMMIT;
