-- eone_connect v1: job schedulati, storico esecuzioni, stato di sincronizzazione

CREATE TABLE eone_connect.job (
    code          varchar(60)  PRIMARY KEY,
    job_key       varchar(120) NOT NULL,                 -- implementazione registrata dall'applicazione
    description   varchar(200),
    schedule      varchar(200) NOT NULL,                 -- "every 30s" | "daily 06:00 mon-fri" ...
    zone          varchar(40)  NOT NULL DEFAULT 'Europe/Rome',
    enabled       boolean      NOT NULL DEFAULT true,
    params        jsonb        NOT NULL DEFAULT '{}',
    next_run_at   timestamptz,
    last_idle_at  timestamptz,                           -- ultimo giro senza niente da fare (non va nello storico)
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE eone_connect.job_run (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_code      varchar(60)  NOT NULL REFERENCES eone_connect.job(code) ON DELETE CASCADE,
    node          varchar(100),                          -- istanza che l'ha eseguito
    started_at    timestamptz  NOT NULL DEFAULT now(),
    ended_at      timestamptz,
    status        varchar(10)  NOT NULL CHECK (status IN ('RUNNING','OK','WARNING','ERROR','ABORTED')),
    message       text,
    counts        jsonb        NOT NULL DEFAULT '{}'
);
CREATE INDEX ON eone_connect.job_run (job_code, started_at DESC);
CREATE INDEX ON eone_connect.job_run (started_at);

-- da quando rileggere ogni entita' sincronizzata (generalizza tabfcssync)
CREATE TABLE eone_connect.sync_state (
    scope         varchar(60)  NOT NULL,                 -- sistema/tenant di origine
    entity        varchar(60)  NOT NULL,                 -- EKET, MARA, PURCHASE_ORDER ...
    watermark     varchar(100),                          -- timestamp o chiave da cui ripartire
    status        varchar(10)  NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','RUNNING','OK','ERROR')),
    message       text,
    started_at    timestamptz,
    finished_at   timestamptz,
    duration_ms   bigint,
    items         bigint,
    PRIMARY KEY (scope, entity)
);
