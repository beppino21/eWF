-- eWF: migrazione schema 0.15 -> 0.16
-- Designer dei processi: l'applicazione (ewf_app) modifica le definizioni, ma SOLO delle versioni in bozza.
-- Il controllo e' gia' nel servizio; questi trigger lo ripetono nel database, cosi' nemmeno un errore del
-- codice puo' cambiare una versione attiva o ritirata (su cui girano o sono girati dei processi).
-- Valgono solo per ewf_app: script e migrazioni eseguiti da ewf_owner non sono toccati.
-- Eseguire come ewf_owner, poi rieseguire ewf_grants_app.sql.
BEGIN;

-- Versione a cui appartiene una riga di definizione
CREATE OR REPLACE FUNCTION ewf_core.def_version_of_step(p_step bigint) RETURNS bigint
LANGUAGE sql STABLE AS $$ SELECT version_id FROM ewf_core.step_def WHERE id = p_step $$;

CREATE OR REPLACE FUNCTION ewf_core.def_version_of_task(p_task bigint) RETURNS bigint
LANGUAGE sql STABLE AS $$
    SELECT s.version_id FROM ewf_core.task_def d JOIN ewf_core.step_def s ON s.id = d.step_id WHERE d.id = p_task
$$;

CREATE OR REPLACE FUNCTION ewf_core.def_require_draft(p_version bigint) RETURNS void
LANGUAGE plpgsql STABLE AS $$
DECLARE v_status varchar;
BEGIN
    IF p_version IS NULL THEN RETURN; END IF;
    SELECT status INTO v_status FROM ewf_core.process_version WHERE id = p_version;
    IF v_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'Versione % non in bozza (%): la definizione non si puo'' modificare', p_version, v_status
            USING ERRCODE = 'check_violation';
    END IF;
END $$;

-- step, task, agganci, opzioni, funzioni: solo nelle bozze
CREATE OR REPLACE FUNCTION ewf_core.guard_definition_row() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_user <> 'ewf_app' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_TABLE_NAME = 'step_def' THEN
        IF TG_OP <> 'INSERT' THEN PERFORM ewf_core.def_require_draft(OLD.version_id); END IF;
        IF TG_OP <> 'DELETE' THEN PERFORM ewf_core.def_require_draft(NEW.version_id); END IF;
    ELSIF TG_TABLE_NAME = 'task_def' THEN
        IF TG_OP <> 'INSERT' THEN PERFORM ewf_core.def_require_draft(ewf_core.def_version_of_step(OLD.step_id)); END IF;
        IF TG_OP <> 'DELETE' THEN PERFORM ewf_core.def_require_draft(ewf_core.def_version_of_step(NEW.step_id)); END IF;
    ELSE   -- task_hook, task_option, task_function
        IF TG_OP <> 'INSERT' THEN PERFORM ewf_core.def_require_draft(ewf_core.def_version_of_task(OLD.task_def_id)); END IF;
        IF TG_OP <> 'DELETE' THEN PERFORM ewf_core.def_require_draft(ewf_core.def_version_of_task(NEW.task_def_id)); END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

DROP TRIGGER IF EXISTS guard_definition ON ewf_core.step_def;
DROP TRIGGER IF EXISTS guard_definition ON ewf_core.task_def;
DROP TRIGGER IF EXISTS guard_definition ON ewf_core.task_hook;
DROP TRIGGER IF EXISTS guard_definition ON ewf_core.task_option;
DROP TRIGGER IF EXISTS guard_definition ON ewf_core.task_function;
CREATE TRIGGER guard_definition BEFORE INSERT OR UPDATE OR DELETE ON ewf_core.step_def
    FOR EACH ROW EXECUTE FUNCTION ewf_core.guard_definition_row();
CREATE TRIGGER guard_definition BEFORE INSERT OR UPDATE OR DELETE ON ewf_core.task_def
    FOR EACH ROW EXECUTE FUNCTION ewf_core.guard_definition_row();
CREATE TRIGGER guard_definition BEFORE INSERT OR UPDATE OR DELETE ON ewf_core.task_hook
    FOR EACH ROW EXECUTE FUNCTION ewf_core.guard_definition_row();
CREATE TRIGGER guard_definition BEFORE INSERT OR UPDATE OR DELETE ON ewf_core.task_option
    FOR EACH ROW EXECUTE FUNCTION ewf_core.guard_definition_row();
CREATE TRIGGER guard_definition BEFORE INSERT OR UPDATE OR DELETE ON ewf_core.task_function
    FOR EACH ROW EXECUTE FUNCTION ewf_core.guard_definition_row();

-- versioni: si creano in bozza, si cancellano solo le bozze; una versione attiva puo' solo essere ritirata
CREATE OR REPLACE FUNCTION ewf_core.guard_process_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_user <> 'ewf_app' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Una nuova versione nasce in bozza' USING ERRCODE = 'check_violation';
    ELSIF TG_OP = 'DELETE' AND OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Si possono eliminare solo le versioni in bozza' USING ERRCODE = 'check_violation';
    ELSIF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT' THEN
        IF NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED'
                AND NEW.process_type_code = OLD.process_type_code AND NEW.version_no = OLD.version_no
                AND NEW.end_handler_id IS NOT DISTINCT FROM OLD.end_handler_id
                AND NEW.end_handler_config = OLD.end_handler_config) THEN
            RAISE EXCEPTION 'Versione % (%): si puo'' solo ritirare quella attiva', OLD.id, OLD.status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;

DROP TRIGGER IF EXISTS guard_process_version ON ewf_core.process_version;
CREATE TRIGGER guard_process_version BEFORE INSERT OR UPDATE OR DELETE ON ewf_core.process_version
    FOR EACH ROW EXECUTE FUNCTION ewf_core.guard_process_version();

COMMIT;
