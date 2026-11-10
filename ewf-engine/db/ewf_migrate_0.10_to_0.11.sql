-- eWF: migrazione schema 0.10 -> 0.11
-- Avvio automatico dalla coda del sistema di origine: regole di avvio, tolleranza sulle modifiche.
-- Eseguire come ewf_owner.
BEGIN;

-- Documenti senza strategia di rilascio (evento CHANGED): variazione % di valore o quantita'
-- oltre la quale il processo aperto riparte. 0 = qualsiasi variazione.
ALTER TABLE ewf_core.process_type
    ADD COLUMN change_tolerance_pct numeric(5,2) NOT NULL DEFAULT 0 CHECK (change_tolerance_pct >= 0);

-- Regole di avvio: quale processo per un documento nuovo
CREATE TABLE ewf_core.start_rule (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_type          varchar(20)  NOT NULL REFERENCES ewf_core.doc_type(code),
    conditions        jsonb        NOT NULL DEFAULT '{}',   -- attributi del documento (DOC_INFO); "*" = qualsiasi
    priority          integer      NOT NULL DEFAULT 0,      -- vince la piu' alta; a parita' la piu' specifica
    process_type_code varchar(20)  NOT NULL REFERENCES ewf_core.process_type(code),
    active            boolean      NOT NULL DEFAULT true
);
CREATE INDEX ON ewf_core.start_rule (doc_type) WHERE active;

-- tabella nuova: permessi per l'applicazione (se il ruolo esiste)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ewf_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON ewf_core.start_rule TO ewf_app;
    END IF;
END $$;

COMMIT;
