-- eWF: migrazione schema 0.14 -> 0.15
-- Allegati: nuova modalita' BOTH per tipo processo (allegati del documento nel sistema di origine + allegati in
-- eWF, in un elenco unico). Eseguire come ewf_owner.
BEGIN;

ALTER TABLE ewf_core.process_type DROP CONSTRAINT IF EXISTS process_type_attachment_scope_check;
ALTER TABLE ewf_core.process_type ADD CONSTRAINT process_type_attachment_scope_check
    CHECK (attachment_scope IN ('PROCESS', 'DOCUMENT', 'BOTH'));

COMMENT ON COLUMN ewf_core.process_type.attachment_scope IS
    'Dove stanno gli allegati: PROCESS = in eWF; DOCUMENT = nel sistema di origine; BOTH = entrambi (nuovi file in eWF)';

COMMIT;
