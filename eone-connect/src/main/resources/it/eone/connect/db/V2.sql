-- eone-connect V2: coda di uscita delle mail.
-- Chi vuole mandare una mail scrive una riga (anche nella propria transazione); il job eone.mail-send la spedisce.

CREATE TABLE eone_connect.mail_outbox (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source        varchar(30)   NOT NULL,                 -- applicazione che l'ha generata (ewf, ticket, ...)
    ref           varchar(100),                           -- riferimento applicativo (es. task 8000000026)
    dedup_key     varchar(200)  UNIQUE,                   -- stessa chiave = stessa mail: la seconda viene ignorata
    to_addr       text          NOT NULL,                 -- indirizzi separati da virgola
    cc_addr       text,
    subject       varchar(255)  NOT NULL,
    body_text     text          NOT NULL,
    body_html     text,
    status        varchar(8)    NOT NULL DEFAULT 'NEW'
                  CHECK (status IN ('NEW','SENDING','SENT','ERROR','DEAD')),
    attempts      integer       NOT NULL DEFAULT 0,
    next_try_at   timestamptz   NOT NULL DEFAULT now(),
    last_error    text,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    claimed_at    timestamptz,
    sent_at       timestamptz
);
-- da spedire: nuove ed errori da ritentare, quando e' il momento
CREATE INDEX mail_outbox_due ON eone_connect.mail_outbox (next_try_at) WHERE status IN ('NEW','ERROR');
CREATE INDEX mail_outbox_ref ON eone_connect.mail_outbox (source, ref);
