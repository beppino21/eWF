-- eWF: migrazione schema 0.13 -> 0.14
-- Applicazione transazionale: login con utenti eWF, commenti e allegati di processo, configurazione della lista
-- delle attivita' per tipo di processo, funzioni speciali per task. Eseguire come ewf_owner.
BEGIN;

-- Login con utenti propri di eWF: solo l'hash della password (PBKDF2), mai la password.
ALTER TABLE ewf_org.app_user
    ADD COLUMN password_hash        varchar(200),
    ADD COLUMN password_must_change boolean      NOT NULL DEFAULT true,
    ADD COLUMN last_login_at        timestamptz,
    ADD COLUMN failed_logins        integer      NOT NULL DEFAULT 0,
    ADD COLUMN locked_until         timestamptz;                  -- blocco temporaneo dopo troppi tentativi falliti

-- Lista delle attivita' e allegati: configurazione per tipo di processo.
ALTER TABLE ewf_core.process_type
    -- riepilogo leggibile nella lista mista, es. '{sap.LIFNR} {supplier_name} - {plant} {plant_name}'
    ADD COLUMN list_summary     varchar(500),
    -- colonne specifiche nella lista filtrata su questo processo: [{"label":"Fornitore","key":"supplier_name"}, ...]
    ADD COLUMN list_columns     jsonb        NOT NULL DEFAULT '[]',
    -- semaforo dei giorni di attesa: verde fino a yellow-1, giallo fino a red-1, poi rosso
    ADD COLUMN sla_yellow_days  integer      NOT NULL DEFAULT 6  CHECK (sla_yellow_days > 0),
    ADD COLUMN sla_red_days     integer      NOT NULL DEFAULT 11 CHECK (sla_red_days > 0),
    -- dove stanno gli allegati: PROCESS = DB eWF, DOCUMENT = sistema di origine (es. GOS dell'OdA)
    ADD COLUMN attachment_scope varchar(10)  NOT NULL DEFAULT 'PROCESS' CHECK (attachment_scope IN ('PROCESS','DOCUMENT')),
    ADD CONSTRAINT process_type_sla_check CHECK (sla_red_days > sla_yellow_days);

-- Commenti e allegati usano le tabelle gia' presenti dalla 0.1 (ewf_core.comment, ewf_core.attachment).
-- Commenti: kind 'N' = nota dell'utente; le note di OK/KO/sposta restano sul task (storico), non qui.
-- Allegati provider LOCAL: il contenuto sta in attachment_content, separato dai metadati (le liste non leggono
-- i binari; in futuro si potra' spostare l'archiviazione altrove usando attachment.storage_key).
CREATE TABLE ewf_core.attachment_content (
    attachment_id bigint       PRIMARY KEY REFERENCES ewf_core.attachment(id),
    content       bytea        NOT NULL
);

-- Funzioni speciali per task: pulsanti aggiuntivi nel dettaglio, eseguiti da un handler.
CREATE TABLE ewf_core.task_function (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_def_id   bigint       NOT NULL REFERENCES ewf_core.task_def(id),
    code          varchar(30)  NOT NULL,
    label         varchar(60)  NOT NULL,
    handler_code  varchar(60)  NOT NULL REFERENCES ewf_core.handler(code),
    confirm_text  varchar(200),                         -- se presente, chiedere conferma prima di eseguire
    position      integer      NOT NULL DEFAULT 0,
    UNIQUE (task_def_id, code)
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ewf_app') THEN
        GRANT SELECT, INSERT ON ewf_core.comment, ewf_core.attachment, ewf_core.attachment_content TO ewf_app;
        GRANT UPDATE (deleted_at, deleted_by) ON ewf_core.attachment TO ewf_app;
        GRANT SELECT ON ewf_core.task_function TO ewf_app;
        GRANT UPDATE (password_hash, password_must_change, last_login_at, failed_logins, locked_until, updated_at) ON ewf_org.app_user TO ewf_app;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ewf_core TO ewf_app;
    END IF;
END $$;

COMMIT;
