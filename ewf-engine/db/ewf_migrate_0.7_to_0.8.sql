-- eWF: migrazione schema 0.7 -> 0.8
-- Rami per opzione: nodi di scelta, opzioni, varianti di step.
-- Eseguire come ewf_owner (o come postgres dopo SET ROLE ewf_owner).
-- Tutte modifiche "in place": i permessi gia' concessi a ewf_app restano validi.
BEGIN;

-- Varianti di step: stesso numero, una per opzione (NULL = tronco comune)
ALTER TABLE ewf_core.step_def ADD COLUMN option_code varchar(10);
ALTER TABLE ewf_core.step_def DROP CONSTRAINT step_def_version_id_step_no_key;
CREATE UNIQUE INDEX step_def_variant
    ON ewf_core.step_def (version_id, step_no, coalesce(option_code, ''));

-- Opzioni del nodo di scelta (la vecchia struttura TASKOPZIONI non e' mai stata usata: tabella vuota)
ALTER TABLE ewf_core.task_option DROP CONSTRAINT task_option_pkey;
ALTER TABLE ewf_core.task_option
    DROP COLUMN text_id, DROP COLUMN text_name, DROP COLUMN add_process_type, DROP COLUMN doc_template,
    DROP COLUMN doc_template_path, DROP COLUMN long_text, DROP COLUMN param_flag, DROP COLUMN description;
ALTER TABLE ewf_core.task_option
    ALTER COLUMN option_code TYPE varchar(10),
    ADD COLUMN label  varchar(60) NOT NULL,
    ADD COLUMN reject boolean     NOT NULL DEFAULT false,
    ADD COLUMN config jsonb       NOT NULL DEFAULT '{}';
ALTER TABLE ewf_core.task_option ADD PRIMARY KEY (task_def_id, option_code);

-- Opzione scelta sul task
ALTER TABLE ewf_core.task_instance ADD COLUMN option_code varchar(10);

COMMIT;
