-- =====================================================================
-- eWF - Modello dati PostgreSQL  v0.10 (bozza per discussione)
-- ---------------------------------------------------------------------
-- Schemi:
--   ewf_org   organizzazione: utenti, ruoli, team, assegnazioni, sostituti
--   ewf_core  motore: definizioni versionate, istanze, handler, audit
--   ewf_ext_* moduli/pacchetti cliente (trasferte, pagamenti, ...) - non qui
--
-- Convenzioni:
--   - id interni bigint identity; i vecchi codici SAP restano come
--     colonne "legacy_*" o come codici naturali (process_type.code)
--   - NULL = "qualsiasi valore" nelle regole (in SAP era il blank)
--   - nessuna tabella *_HST: lo storico e' lo stato; archiviazione a parte
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS ewf_org;
CREATE SCHEMA IF NOT EXISTS ewf_core;

-- =====================================================================
-- ewf_org
-- =====================================================================

-- TB_CLS
CREATE TABLE ewf_org.user_class (
    code              varchar(2)   PRIMARY KEY,
    description       varchar(80)  NOT NULL,
    auth_type         char(1),                    -- CLSTTYP A/B/C
    can_suspend       boolean      NOT NULL DEFAULT false,
    can_delete        boolean      NOT NULL DEFAULT false,
    can_substitute    boolean      NOT NULL DEFAULT false,
    can_sign_consult  boolean      NOT NULL DEFAULT false,
    auth_level        integer,                    -- CLSLIV_AUT
    max_level         integer,                    -- CLSLIV_SUP
    is_admin          boolean      NOT NULL DEFAULT false
);

-- TB_ARR
CREATE TABLE ewf_org.area (
    code              varchar(2)   PRIMARY KEY,
    description       varchar(80)  NOT NULL,
    list_aggregation  char(1),
    vendor_link       varchar(15)
);

-- TB_TEAM
CREATE TABLE ewf_org.team (
    code              varchar(20)  PRIMARY KEY,
    description       varchar(80)  NOT NULL
);

-- ROLES
CREATE TABLE ewf_org.role (
    code              varchar(20)  PRIMARY KEY,
    description       varchar(80)  NOT NULL
);

-- USER0  (niente password: l'autenticazione e' dell'IdP)
CREATE TABLE ewf_org.app_user (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login             varchar(64)  NOT NULL UNIQUE,   -- ex UNAME
    display_name      varchar(120) NOT NULL,          -- NAME1 + NAME2
    email             varchar(241),
    mobile            varchar(20),
    idp_issuer        varchar(255),
    idp_subject       varchar(255),                   -- 'sub' OIDC
    sap_user          varchar(12),                    -- ex UNAME_SAP (opzionale)
    weight            integer      NOT NULL DEFAULT 0,-- ex PUNTEGGIO ("peso"); vedi effective_weight()
    user_class_code   varchar(2)   REFERENCES ewf_org.user_class(code),
    area_code         varchar(2)   REFERENCES ewf_org.area(code),
    plant             varchar(4),                     -- ZWF_WERKS
    purchasing_group  varchar(3),                     -- EKGRP
    initials          varchar(5),                     -- SIGLA
    signature         text,                           -- FIRMA/FIRMA2/FIRMA3
    oper_level        char(1),                        -- LIV_OPER
    channel           varchar(3),
    mail_every_days   integer,                        -- GG_MAIL
    last_mail_on      date,
    -- override per utente; NULL = vale quanto definito dalla classe
    can_suspend       boolean,
    can_delete        boolean,
    can_substitute    boolean,
    hidden_in_lists   boolean      NOT NULL DEFAULT false,   -- NO_LISTE
    active            boolean      NOT NULL DEFAULT true,    -- NOT ANNULLATO
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (idp_issuer, idp_subject)
);

-- Team come anagrafica: un utente puo' stare in piu' team (ex USER0.TEAM)
CREATE TABLE ewf_org.team_member (
    team_code         varchar(20)  NOT NULL REFERENCES ewf_org.team(code),
    user_id           bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    valid_from        date,
    valid_to          date,
    PRIMARY KEY (team_code, user_id)
);
CREATE INDEX ON ewf_org.team_member (user_id);

-- USER_TASK: matrice di determinazione assegnatario
CREATE TABLE ewf_org.assignment_rule (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_type_code varchar(20),                    -- FK logica verso ewf_core
    role_code         varchar(20)  NOT NULL REFERENCES ewf_org.role(code),
    purchasing_group  varchar(3),
    plant             varchar(4),
    storage_location  varchar(4),
    amount_from       numeric(15,2),
    amount_to         numeric(15,2),
    currency          varchar(5),
    budget            numeric(15,2),
    -- destinatario: un utente, un team, o l'utente con il suo team
    user_id           bigint       REFERENCES ewf_org.app_user(id),
    target_team_code  varchar(20)  REFERENCES ewf_org.team(code),
    valid_from        date,
    valid_to          date,
    CHECK (user_id IS NOT NULL OR target_team_code IS NOT NULL)
);
CREATE INDEX ON ewf_org.assignment_rule (process_type_code, role_code);

-- WF_CLI_USER: eccezioni di assegnazione
CREATE TABLE ewf_org.assignment_exception (
    process_type_code varchar(20)  NOT NULL,
    role_code         varchar(20)  NOT NULL REFERENCES ewf_org.role(code),
    for_user_id       bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    authorizer_id     bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    PRIMARY KEY (process_type_code, role_code, for_user_id)
);

-- CDO_USERGRP: gruppi utenti intercambiabili
CREATE TABLE ewf_org.role_group_member (
    process_type_code varchar(20)  NOT NULL,
    role_code         varchar(20)  NOT NULL REFERENCES ewf_org.role(code),
    seq               integer      NOT NULL,
    user_id           bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    PRIMARY KEY (process_type_code, role_code, seq)
);

-- CDO_AUTH
CREATE TABLE ewf_org.role_team_auth (
    process_type_code varchar(20)  NOT NULL,
    role_code         varchar(20)  NOT NULL REFERENCES ewf_org.role(code),
    team_code         varchar(20)  NOT NULL REFERENCES ewf_org.team(code),
    PRIMARY KEY (process_type_code, role_code, team_code)
);

-- USR_AUTH
CREATE TABLE ewf_org.user_process_auth (
    user_id           bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    process_type_code varchar(20)  NOT NULL,
    auth_level        integer,
    active            boolean      NOT NULL DEFAULT true,
    changed_at        timestamptz  NOT NULL DEFAULT now(),
    changed_by        bigint       REFERENCES ewf_org.app_user(id),
    PRIMARY KEY (user_id, process_type_code)
);

-- SOSTITUTI
CREATE TABLE ewf_org.substitution (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    substitute_id     bigint       NOT NULL REFERENCES ewf_org.app_user(id),
    default_subst_id  bigint       REFERENCES ewf_org.app_user(id), -- USER_SOST_PARK
    valid_from        date         NOT NULL,
    valid_to          date         NOT NULL,
    changed_by        bigint       REFERENCES ewf_org.app_user(id),
    changed_at        timestamptz  NOT NULL DEFAULT now(),
    CHECK (valid_to >= valid_from),
    CHECK (substitute_id <> user_id)
);
CREATE INDEX ON ewf_org.substitution (user_id, valid_from, valid_to);

-- =====================================================================
-- ewf_core - configurazione tecnica
-- =====================================================================

-- Sistemi backend (un cliente puo' averne piu' di uno). Niente segreti qui:
-- connection_ref punta alla configurazione esterna (env / vault / file).
CREATE TABLE ewf_core.backend_system (
    code              varchar(20)  PRIMARY KEY,
    description       varchar(80)  NOT NULL,
    kind              varchar(20)  NOT NULL
                      CHECK (kind IN ('ECC','S4_PRIVATE','S4_ONPREM','S4_PUBLIC','OTHER')),
    connection_ref    varchar(80)  NOT NULL,
    active            boolean      NOT NULL DEFAULT true
);

-- PARAMETRI
CREATE TABLE ewf_core.parameter (
    key               varchar(40)  PRIMARY KEY,
    num_value         bigint,
    text_value        varchar(255),
    description       varchar(80)
);

-- Catalogo handler riutilizzabili (FM RFC, API, classi Java, passi interattivi)
CREATE TABLE ewf_core.handler (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              varchar(60)  NOT NULL UNIQUE,   -- es. '/EWF/WF_RIL_ODA'
    kind              varchar(20)  NOT NULL
                      CHECK (kind IN ('RFC_FM','API','JAVA','INTERACTIVE')),
    contract          varchar(30)  NOT NULL
                      CHECK (contract IN ('TASK_ACTION','ASSIGNEE','PROCESS_END','DOC_ADAPTER')),
    target            varchar(255) NOT NULL,          -- nome FM / classe / template URL
    backend_code      varchar(20)  REFERENCES ewf_core.backend_system(code),
    module            varchar(30),                    -- NULL = core, altrimenti pacchetto cliente
    idempotent        boolean      NOT NULL DEFAULT false,
    config            jsonb        NOT NULL DEFAULT '{}',
    description       varchar(120)
);

-- Tipi documento (ex Z_WF_TIPODOC: F, B, FA, FI_DOC, C, IO, CM...)
-- L'adapter interpreta la chiave, calcola valore e attributi di contesto.
CREATE TABLE ewf_core.doc_type (
    code              varchar(20)  PRIMARY KEY,
    description       varchar(80)  NOT NULL,
    adapter_handler_id bigint      REFERENCES ewf_core.handler(id),
    key_format        varchar(80),                   -- es. 'EBELN(10)' / 'BUKRS+BELNR+GJAHR'
    module            varchar(30)
);

-- =====================================================================
-- ewf_core - definizioni (versionate)
-- =====================================================================

-- PROCESSO + PROCTYPE
CREATE TABLE ewf_core.process_type (
    code              varchar(20)  PRIMARY KEY,
    description       varchar(80)  NOT NULL,
    doc_type          varchar(20)  NOT NULL REFERENCES ewf_core.doc_type(code),
    module            varchar(30),                    -- NULL = core
    ref_class         varchar(30),                    -- Z_WF_CLASSNAME
    attachment_class  varchar(2),                     -- Z_WF_CLASSTYPE
    manual_start      boolean      NOT NULL DEFAULT false,
    lock_changes      boolean      NOT NULL DEFAULT false,
    suspended         boolean      NOT NULL DEFAULT false,
    mail_at_end       boolean      NOT NULL DEFAULT false,
    fictitious        boolean      NOT NULL DEFAULT false,
    no_first_step     boolean      NOT NULL DEFAULT false,
    auto_ok_first     boolean      NOT NULL DEFAULT false,
    legacy_flags      jsonb        NOT NULL DEFAULT '{}' -- NO_WF00, NO_MULTI, path archivio...
);

CREATE TABLE ewf_core.process_version (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_type_code varchar(20)  NOT NULL REFERENCES ewf_core.process_type(code),
    version_no        integer      NOT NULL,
    status            varchar(10)  NOT NULL DEFAULT 'DRAFT'
                      CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    end_handler_id    bigint       REFERENCES ewf_core.handler(id), -- Z_WF_FM_END_PROC
    end_handler_config jsonb       NOT NULL DEFAULT '{}',          -- es. {"Z_WF_SET_RIL":"01"}
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        bigint       REFERENCES ewf_org.app_user(id),
    activated_at      timestamptz,
    note              text,
    UNIQUE (process_type_code, version_no)
);
-- una sola versione attiva per tipo processo
CREATE UNIQUE INDEX process_version_one_active
    ON ewf_core.process_version (process_type_code) WHERE status = 'ACTIVE';

-- STEP (da TASK: Z_WF_STEP). Transizioni e join sono dello step.
-- option_code: NULL = tronco comune; valorizzato = variante dello step valida solo se l'opzione
-- e' tra le scelte del processo (sostituisce lo step del tronco con lo stesso numero).
CREATE TABLE ewf_core.step_def (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id        bigint       NOT NULL REFERENCES ewf_core.process_version(id),
    step_no           integer      NOT NULL,
    is_first          boolean      NOT NULL DEFAULT false,
    join_policy       varchar(5)   NOT NULL DEFAULT 'ALL'
                      CHECK (join_policy IN ('ALL','ANY')),
    next_step_ok      integer,                        -- NULL = fine processo (ex 99999)
    next_step_ko      integer,
    -- step richiesto solo se valore documento >= soglia (ex Z_WF_LIMIT)
    min_amount        numeric(15,2),
    min_amount_currency varchar(5),
    option_code       varchar(10)
);
CREATE UNIQUE INDEX step_def_variant
    ON ewf_core.step_def (version_id, step_no, coalesce(option_code, ''));

-- TASK di uno step (da TASK: Z_WF_SUBOP). Piu' righe = task paralleli.
CREATE TABLE ewf_core.task_def (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    step_id           bigint       NOT NULL REFERENCES ewf_core.step_def(id),
    subop_no          integer      NOT NULL DEFAULT 0,
    text              varchar(80)  NOT NULL,
    role_code         varchar(20)  REFERENCES ewf_org.role(code),
    required_weight   integer      NOT NULL DEFAULT 0, -- Z_WF_LIV: peso effettivo >= peso task
    -- RULES   = risolutore su assignment_rule        (ex /EWF/WF_USER_TASK)
    -- HANDLER = handler di assegnazione               (FM specifiche)
    -- STARTER = chi ha avviato il processo            (ex SY_START, USER_TASK_MIGO)
    -- ACTOR   = chi ha eseguito il task precedente    (ex SY_UNAME)
    -- PREVIOUS= assegnatario del task precedente      (ex >>>SKIP_TASK)
    assignee_mode     varchar(10)  NOT NULL DEFAULT 'RULES'
                      CHECK (assignee_mode IN ('RULES','HANDLER','STARTER','ACTOR','PREVIOUS')),
    assignee_handler_id bigint     REFERENCES ewf_core.handler(id),
    team_task         boolean      NOT NULL DEFAULT false, -- visibile e firmabile dai membri del team
    team_code         varchar(20)  REFERENCES ewf_org.team(code), -- team fisso; NULL = dal risolutore
    preview_mode      varchar(6)   NOT NULL DEFAULT 'NAME'
                      CHECK (preview_mode IN ('NAME','ROLE','HIDDEN')),
    interaction       varchar(12)  NOT NULL DEFAULT 'WEB'
                      CHECK (interaction IN ('WEB','INTERACTIVE','SAPGUI_ONLY')), -- ex Z_NO_WEB
    free_document     boolean      NOT NULL DEFAULT false, -- "non blocca origine"
    view_info         varchar(30),
    task_param        varchar(20),                     -- ZWF_TASK_PARAM
    grouping          boolean      NOT NULL DEFAULT false,
    multi_allowed     boolean      NOT NULL DEFAULT false,
    UNIQUE (step_id, subop_no)
);

-- Hook evento -> handler
--   BEFORE_ACTION ex FM_EXANTE   : prima di OK/KO, puo' bloccare l'azione
--   ON_OK         ex FM_OK       : esecuzione su approvazione (prima dell'avanzamento)
--   ON_KO         ex FM_KO       : esecuzione su rifiuto
--   ON_ACTIVATE   ex FM_EXPOST   : alla creazione del task (oggi: mail al destinatario)
--   BUTTON        ex FM_BUTTON01 : azione aggiuntiva su richiesta dell'utente
CREATE TABLE ewf_core.task_hook (
    task_def_id       bigint       NOT NULL REFERENCES ewf_core.task_def(id),
    event             varchar(15)  NOT NULL
                      CHECK (event IN ('BEFORE_ACTION','ON_OK','ON_KO','ON_ACTIVATE','BUTTON')),
    seq               integer      NOT NULL DEFAULT 1,
    handler_id        bigint       NOT NULL REFERENCES ewf_core.handler(id),
    label             varchar(30),                     -- per BUTTON
    config            jsonb        NOT NULL DEFAULT '{}', -- parametri dell'aggancio, es. {"release_code":"01"}
    PRIMARY KEY (task_def_id, event, seq)
);

-- Opzioni di un nodo di scelta: se il task ne ha, al posto di Approva/Respingi l'utente sceglie un'opzione.
--   reject = false: prosegue verso next_step_ok dello step; true: verso next_step_ko (NULL = fine negativa).
--   Il codice e' univoco nella versione: le varianti di step (step_def.option_code) lo usano per il ramo.
--   handler_id: eseguito alla scelta, prima degli agganci ON_OK/ON_KO del task.
CREATE TABLE ewf_core.task_option (
    task_def_id       bigint       NOT NULL REFERENCES ewf_core.task_def(id),
    option_code       varchar(10)  NOT NULL,
    seq               integer      NOT NULL,
    handler_id        bigint       REFERENCES ewf_core.handler(id),
    label             varchar(60)  NOT NULL,
    reject            boolean      NOT NULL DEFAULT false,
    config            jsonb        NOT NULL DEFAULT '{}',
    -- processo da avviare sullo stesso documento (HANDOFF: questo si chiude FORWARDED; PARALLEL: prosegue)
    start_process_type varchar(20),
    spawn_mode        varchar(10)  CHECK (spawn_mode IN ('HANDOFF','PARALLEL')),
    PRIMARY KEY (task_def_id, option_code)
);

-- =====================================================================
-- ewf_core - runtime
-- =====================================================================

-- PROCDOC + PROCRUN (unificate)
-- id da 8000000001: compatibili con NUMC10 di /EWF/ e distinti dai PROCID legacy
CREATE TABLE ewf_core.process_instance (
    id                bigint GENERATED ALWAYS AS IDENTITY (START WITH 8000000001) PRIMARY KEY,
    version_id        bigint       NOT NULL REFERENCES ewf_core.process_version(id),
    process_type_code varchar(20)  NOT NULL REFERENCES ewf_core.process_type(code),
    backend_code      varchar(20)  REFERENCES ewf_core.backend_system(code),
    doc_type          varchar(20)  NOT NULL,
    document_key      varchar(40)  NOT NULL,           -- Z_WF_DOCUMENT
    text              varchar(80),
    status            varchar(10)  NOT NULL DEFAULT 'RUNNING'
                      CONSTRAINT process_instance_status_check
                      CHECK (status IN ('RUNNING','SUSPENDED','COMPLETING','COMPLETED','REJECTED','CANCELLED','ERROR','FORWARDED')),
    status_reason     varchar(255),                    -- END_FAILED: ..., UNKNOWN_EXECUTION:n, RESTARTED
    currency          varchar(5),
    net_value         numeric(15,2),
    extra_budget      integer,
    started_at        timestamptz  NOT NULL DEFAULT now(),
    started_by        bigint       REFERENCES ewf_org.app_user(id),
    ended_at          timestamptz,
    ended_by          bigint       REFERENCES ewf_org.app_user(id),
    correlation_key   uuid         NOT NULL DEFAULT gen_random_uuid(), -- scritta nei documenti SAP
    legacy_procid     bigint       UNIQUE,              -- ex Z_WF_PROCID
    doc_context       jsonb        NOT NULL DEFAULT '{}', -- attributi del documento letti all'avvio (regole)
    row_version       integer      NOT NULL DEFAULT 0   -- lock ottimistico
);
CREATE INDEX ON ewf_core.process_instance (doc_type, document_key);
CREATE INDEX ON ewf_core.process_instance (status) WHERE status IN ('RUNNING','SUSPENDED','COMPLETING','ERROR');
-- un solo processo aperto per tipo processo + documento (controllo di WF_START)
CREATE UNIQUE INDEX process_instance_one_open
    ON ewf_core.process_instance (process_type_code, doc_type, document_key, coalesce(backend_code, ''))
 WHERE status IN ('RUNNING','SUSPENDED','COMPLETING','ERROR');

-- Stato degli step della singola istanza (ex Z_WF_SKIP / VIEW_INFO='DELETE' su TASKDOC).
-- Scritto SOLO dal motore, anche su istruzione di un handler (source_execution_id).
CREATE TABLE ewf_core.instance_step_state (
    process_id        bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    step_no           integer      NOT NULL,
    skipped           boolean      NOT NULL DEFAULT false,
    reason            varchar(30)  NOT NULL,          -- AMOUNT_BELOW_MIN, HANDLER, MANUAL
    source_execution_id bigint,                       -- FK aggiunta dopo handler_execution
    changed_by        bigint       REFERENCES ewf_org.app_user(id),
    changed_at        timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (process_id, step_no)
);

-- TASKRUN
CREATE TABLE ewf_core.task_instance (
    id                bigint GENERATED ALWAYS AS IDENTITY (START WITH 8000000001) PRIMARY KEY,
    process_id        bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    task_def_id       bigint       NOT NULL REFERENCES ewf_core.task_def(id),
    step_no           integer      NOT NULL,
    subop_no          integer      NOT NULL DEFAULT 0,
    status            varchar(10)  NOT NULL DEFAULT 'OPEN'
                      CHECK (status IN ('OPEN','EXECUTING','OK','KO','MOVED','CANCELLED','SKIPPED','ERROR')),
    assignee_id       bigint       REFERENCES ewf_org.app_user(id),
    team_code         varchar(20)  REFERENCES ewf_org.team(code),  -- task di team
    claimed_by_id     bigint       REFERENCES ewf_org.app_user(id),-- presa in carico
    claimed_at        timestamptz,
    role_code         varchar(20)  REFERENCES ewf_org.role(code),
    from_user_id      bigint       REFERENCES ewf_org.app_user(id),  -- Z_WF_FROM_USER
    moved_to_id       bigint       REFERENCES ewf_org.app_user(id),  -- Z_WF_MOVE_TO
    acted_by_id       bigint       REFERENCES ewf_org.app_user(id),  -- chi ha agito davvero
    acted_as_subst    boolean      NOT NULL DEFAULT false,           -- Z_WF_SOSTITUTO
    action            varchar(70),                                   -- Z_WF_AZIONE
    option_code       varchar(10),                                   -- opzione scelta (nodi di scelta)
    note              text,
    user_field1       varchar(20),
    user_field2       varchar(20),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    closed_at         timestamptz,
    legacy_taskid     bigint,
    row_version       integer      NOT NULL DEFAULT 0
);
CREATE INDEX task_inbox ON ewf_core.task_instance (assignee_id) WHERE status = 'OPEN';
CREATE INDEX task_team_inbox ON ewf_core.task_instance (team_code) WHERE status = 'OPEN' AND team_code IS NOT NULL;
CREATE INDEX ON ewf_core.task_instance (process_id, step_no);

-- Esecuzioni handler: stato, idempotenza, riconciliazione
CREATE TABLE ewf_core.handler_execution (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id           bigint       REFERENCES ewf_core.task_instance(id),
    process_id        bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    handler_code      varchar(60)  NOT NULL,
    handler_id        bigint       REFERENCES ewf_core.handler(id),
    event             varchar(15)  NOT NULL,
    attempt           integer      NOT NULL DEFAULT 1,
    idempotency_key   uuid         NOT NULL DEFAULT gen_random_uuid(),
    status            varchar(10)  NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','RUNNING','OK','KO','UNKNOWN')),
    result_message    varchar(255),                   -- ex E_RETURN
    directives        jsonb        NOT NULL DEFAULT '[]', -- istruzioni restituite (SKIP_STEP...) e applicate
    request           jsonb,
    response          jsonb,
    started_at        timestamptz,
    ended_at          timestamptz,
    UNIQUE (idempotency_key)
);
CREATE INDEX ON ewf_core.handler_execution (status) WHERE status IN ('PENDING','RUNNING','UNKNOWN');

ALTER TABLE ewf_core.instance_step_state
    ADD FOREIGN KEY (source_execution_id) REFERENCES ewf_core.handler_execution(id);

-- TASKDOCUM: documenti generati in SAP dal workflow
CREATE TABLE ewf_core.generated_document (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_id        bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    task_id           bigint       REFERENCES ewf_core.task_instance(id),
    execution_id      bigint       REFERENCES ewf_core.handler_execution(id),
    backend_code      varchar(20)  REFERENCES ewf_core.backend_system(code),
    preceding_doc     varchar(20),
    preceding_cat     varchar(4),
    doc_number        varchar(20)  NOT NULL,
    doc_cat           varchar(4),
    doc_year          varchar(4),
    company_code      varchar(4),
    origin            char(1),                        -- ORIGINE_V / ORIGINE_N
    operation         char(1),
    created_by        bigint       REFERENCES ewf_org.app_user(id),
    created_at        timestamptz  NOT NULL DEFAULT now()
);

-- LINKPROCESS
CREATE TABLE ewf_core.process_link (
    parent_id         bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    child_id          bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    link_type         varchar(2)   NOT NULL,
    business_ref      jsonb        NOT NULL DEFAULT '{}', -- LIFNR, EBELN, EBELP...
    PRIMARY KEY (parent_id, child_id)
);

-- COMMENTS
CREATE TABLE ewf_core.comment (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_id        bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    task_id           bigint       REFERENCES ewf_core.task_instance(id),
    kind              char(1),
    author_id         bigint       REFERENCES ewf_org.app_user(id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    body              text         NOT NULL
);

-- ATTACHMENTS + ALLEGATI + documenti esterni: solo metadati, binario altrove
CREATE TABLE ewf_core.attachment (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_id        bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    task_id           bigint       REFERENCES ewf_core.task_instance(id),
    provider          varchar(20)  NOT NULL
                      CHECK (provider IN ('LOCAL','SAP_ARCHIVELINK','SAP_GOS','SAP_TABLE','EXTERNAL_DMS','FILE_LINK')),
    external_ref      jsonb        NOT NULL DEFAULT '{}', -- archiv_id, ar_object, object_id...
    storage_key       varchar(255),                       -- solo per LOCAL
    filename          varchar(255) NOT NULL,
    description       varchar(100),
    mime_type         varchar(128),
    size_bytes        bigint,
    sha256            char(64),
    uploaded_by       bigint       REFERENCES ewf_org.app_user(id),
    uploaded_at       timestamptz  NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    deleted_by        bigint       REFERENCES ewf_org.app_user(id)
);
CREATE INDEX ON ewf_core.attachment (process_id) WHERE deleted_at IS NULL;

-- LOG -> audit append-only (chi, cosa, quando, da dove)
CREATE TABLE ewf_core.audit_event (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at       timestamptz  NOT NULL DEFAULT now(),
    actor_kind        varchar(8)   NOT NULL CHECK (actor_kind IN ('USER','SYSTEM','HANDLER')),
    actor_id          bigint       REFERENCES ewf_org.app_user(id),
    process_id        bigint       REFERENCES ewf_core.process_instance(id),
    task_id           bigint       REFERENCES ewf_core.task_instance(id),
    event_type        varchar(30)  NOT NULL,
    payload           jsonb        NOT NULL DEFAULT '{}',
    client_ip         inet,
    prev_hash         char(64),                           -- catena hash (opzionale)
    hash              char(64)
);
CREATE INDEX ON ewf_core.audit_event (process_id, occurred_at);

CREATE FUNCTION ewf_core.audit_no_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event e'' append-only';
END $$;

CREATE TRIGGER audit_event_immutable
    BEFORE UPDATE OR DELETE ON ewf_core.audit_event
    FOR EACH ROW EXECUTE FUNCTION ewf_core.audit_no_change();

-- =====================================================================
-- Validazione di una versione prima dell'attivazione.
-- Ritorna l'elenco dei problemi; nessuna riga = versione attivabile.
-- =====================================================================
CREATE FUNCTION ewf_core.validate_version(p_version_id bigint)
RETURNS TABLE (severity text, step_no integer, problem text)
LANGUAGE sql STABLE AS $$
    -- esattamente un primo step
    SELECT 'ERROR', NULL::integer,
           format('primi step definiti: %s (atteso 1)', count(*))
      FROM ewf_core.step_def s
     WHERE s.version_id = p_version_id AND s.is_first
    HAVING count(*) <> 1
  UNION ALL
    -- transizioni OK/KO verso step inesistenti (NULL = fine processo)
    SELECT 'ERROR', s.step_no,
           format('%s -> step %s inesistente', t.kind, t.target)
      FROM ewf_core.step_def s
      CROSS JOIN LATERAL (VALUES ('OK', s.next_step_ok), ('KO', s.next_step_ko)) AS t(kind, target)
     WHERE s.version_id = p_version_id
       AND t.target IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM ewf_core.step_def x
                        WHERE x.version_id = p_version_id AND x.step_no = t.target)
  UNION ALL
    -- step senza task
    SELECT 'ERROR', s.step_no, 'step senza task'
      FROM ewf_core.step_def s
     WHERE s.version_id = p_version_id
       AND NOT EXISTS (SELECT 1 FROM ewf_core.task_def d WHERE d.step_id = s.id)
  UNION ALL
    -- task con assegnazione HANDLER ma senza handler
    SELECT 'ERROR', s.step_no, format('task subop %s: assignee_mode HANDLER senza handler', d.subop_no)
      FROM ewf_core.step_def s JOIN ewf_core.task_def d ON d.step_id = s.id
     WHERE s.version_id = p_version_id
       AND d.assignee_mode = 'HANDLER' AND d.assignee_handler_id IS NULL
  UNION ALL
    -- step non raggiungibili dal primo
    SELECT 'WARNING', s.step_no, 'step non raggiungibile dal primo step'
      FROM ewf_core.step_def s
     WHERE s.version_id = p_version_id
       AND s.step_no NOT IN (
            WITH RECURSIVE reach(n) AS (
                SELECT f.step_no FROM ewf_core.step_def f
                 WHERE f.version_id = p_version_id AND f.is_first
              UNION
                SELECT x.step_no
                  FROM reach r
                  JOIN ewf_core.step_def c ON c.version_id = p_version_id AND c.step_no = r.n
                  JOIN ewf_core.step_def x ON x.version_id = p_version_id
                                          AND x.step_no IN (c.next_step_ok, c.next_step_ko)
            )
            SELECT n FROM reach)
$$;

-- =====================================================================
-- Peso effettivo: quello per tipo processo (ex USR_AUTH.LIV_AUT) se attivo,
-- altrimenti quello dell'utente (ex USER0.PUNTEGGIO).
-- =====================================================================
CREATE FUNCTION ewf_org.effective_weight(p_user_id bigint, p_process_type varchar)
RETURNS integer LANGUAGE sql STABLE AS $$
    SELECT coalesce(
             (SELECT a.auth_level FROM ewf_org.user_process_auth a
               WHERE a.user_id = p_user_id AND a.process_type_code = p_process_type
                 AND a.active AND a.auth_level IS NOT NULL),
             (SELECT u.weight FROM ewf_org.app_user u WHERE u.id = p_user_id),
             0)
$$;
