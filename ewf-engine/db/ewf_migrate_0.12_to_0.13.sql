-- eWF: migrazione schema 0.12 -> 0.13
-- Notifiche: avviso immediato per i task che lo prevedono, modelli dei testi delle mail.
-- (Il riepilogo periodico usa ewf_org.app_user.mail_every_days / last_mail_on, gia' presenti.)
-- Eseguire come ewf_owner. Richiede lo schema eone_connect alla versione 2 (coda di uscita delle mail).
BEGIN;

-- Avviso al destinatario quando il task gli viene assegnato. NONE = solo riepilogo periodico.
ALTER TABLE ewf_core.task_def
    ADD COLUMN notify varchar(10) NOT NULL DEFAULT 'NONE' CHECK (notify IN ('NONE','IMMEDIATE'));

-- Notifiche registrate dal motore nella stessa transazione dell'evento; il job ewf.notify le trasforma in mail.
CREATE TABLE ewf_core.notification (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind          varchar(20)  NOT NULL CHECK (kind IN ('TASK_ACTIVATED')),
    process_id    bigint       NOT NULL REFERENCES ewf_core.process_instance(id),
    task_id       bigint       NOT NULL REFERENCES ewf_core.task_instance(id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    result        varchar(200)
);
CREATE INDEX notification_pending ON ewf_core.notification (id) WHERE processed_at IS NULL;

-- Modelli dei testi. Segnaposto {{nome}}; nel corpo HTML i valori vengono resi sicuri (escape).
CREATE TABLE ewf_core.mail_template (
    code          varchar(30)  NOT NULL,
    lang          varchar(5)   NOT NULL DEFAULT 'it',
    subject       varchar(255) NOT NULL,
    body_text     text         NOT NULL,
    body_html     text,
    PRIMARY KEY (code, lang)
);

INSERT INTO ewf_core.mail_template(code, lang, subject, body_text, body_html) VALUES
('TASK_ACTIVATED', 'it',
 'eWF: {{task}} - {{document}}',
 E'Gentile {{user}},\n\nti e'' stata assegnata un''attivita'' da svolgere:\n\n' ||
 E'  {{task}}\n  Processo:   {{process}}\n  Documento:  {{document}}\n  Valore:     {{value}}\n  Fornitore:  {{supplier}}\n\n' ||
 E'{{link}}\n\nQuesta e'' una comunicazione automatica di eWF.',
 '<p>Gentile {{user}},</p><p>ti &egrave; stata assegnata un''attivit&agrave; da svolgere:</p>' ||
 '<table style="border-collapse:collapse"><tr><td colspan="2" style="padding:4px 0"><b>{{task}}</b></td></tr>' ||
 '<tr><td style="padding:2px 12px 2px 0">Processo</td><td>{{process}}</td></tr>' ||
 '<tr><td style="padding:2px 12px 2px 0">Documento</td><td>{{document}}</td></tr>' ||
 '<tr><td style="padding:2px 12px 2px 0">Valore</td><td>{{value}}</td></tr>' ||
 '<tr><td style="padding:2px 12px 2px 0">Fornitore</td><td>{{supplier}}</td></tr></table>' ||
 '<p>{{linkHtml}}</p><p style="color:#777;font-size:smaller">Comunicazione automatica di eWF.</p>'),
('DIGEST', 'it',
 'eWF: {{count}} attivita'' in attesa',
 E'Gentile {{user}},\n\nquesta e'' la situazione delle attivita'' a tuo carico:\n\n{{rows}}\n{{teamRows}}\n' ||
 E'{{link}}\n\nRiceverai il prossimo riepilogo tra {{everyDays}}, se ci saranno ancora attivita'' in attesa.',
 '<p>Gentile {{user}},</p><p>questa &egrave; la situazione delle attivit&agrave; a tuo carico:</p>{{rowsHtml}}{{teamRowsHtml}}' ||
 '<p>{{linkHtml}}</p><p style="color:#777;font-size:smaller">Riceverai il prossimo riepilogo tra {{everyDays}}, ' ||
 'se ci saranno ancora attivit&agrave; in attesa.</p>');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ewf_app') THEN
        GRANT SELECT, INSERT, UPDATE ON ewf_core.notification TO ewf_app;
        GRANT SELECT ON ewf_core.mail_template TO ewf_app;
        GRANT UPDATE (last_mail_on) ON ewf_org.app_user TO ewf_app;     -- data dell'ultimo riepilogo
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ewf_core TO ewf_app;
    END IF;
END $$;

COMMIT;
