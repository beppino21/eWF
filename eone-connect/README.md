# eone-connect

Libreria comune e-One per i "punti di contatto" fra le applicazioni e i sistemi esterni (SAP, WMS, …).
Contiene: **scheduler**, **storico delle esecuzioni**, **stato di sincronizzazione**, **coda di uscita delle mail**.
Prima applicazione che la usa: eWF (coda degli avvii `/EWF/START_Q`). A seguire eTile NEXT e fcs.

Java 21, solo JDBC + PostgreSQL. Nessuna dipendenza da framework.

## Cosa risolve

| Problema | Come |
|---|---|
| Job definiti nel codice, da ricompilare per cambiare orario | Job in tabella `eone_connect.job`: pianificazione, parametri e attivazione si cambiano senza riavvio |
| Due Tomcat che fanno lo stesso lavoro insieme | Lock di PostgreSQL (`pg_try_advisory_lock`) tenuto per tutta l'esecuzione: nel cluster ne gira una sola |
| "Ma il job di stanotte è andato?" | Storico in `eone_connect.job_run`: nodo, inizio, fine, esito, messaggio, conteggi |
| Istanza morta a metà job | Al giro successivo l'esecuzione rimasta `RUNNING` viene chiusa come `ABORTED` |
| Cambio dell'ora legale | `daily 06:00` resta alle 06:00 locali, in qualsiasi giorno dell'anno |
| Coda controllata ogni 30 secondi | I giri senza niente da fare (`JobResult.idle()`) non entrano nello storico; resta solo `last_idle_at` |
| Delta da sistemi esterni | `SyncState`: watermark per (sistema, entità) che avanza solo se il giro va a buon fine |

## Pianificazioni

```
every 30s | every 5m | every 2h
daily 06:00
daily 06:00,18:30 mon-fri
daily 02:00 sun
```

Fuso orario per job (default `Europe/Rome`).

## Installazione dello schema

Con l'utente proprietario del DB, una volta per versione (idempotente):

```
set CONNECT_DB_PASSWORD=...
java -cp eone-connect.jar;postgresql.jar it.eone.connect.db.ConnectSchemaTool jdbc:postgresql://host:5432/ewf ewf_owner ewf_app
```

Il terzo argomento è il ruolo dell'applicazione: riceve lettura/scrittura sulle tabelle, nessun diritto di creare oggetti.
Se lo schema manca, lo scheduler non parte e indica il comando da eseguire.

## Uso

```java
JobRegistry registry = new JobRegistry()
        .register("ewf.start-queue", () -> new StartQueueJob(engine, "EWF_PRD"));

Scheduler s = new Scheduler(new Db(dataSource), registry, Scheduler.Options.defaults());
s.start();
s.store().ensure("EWF_START_QUEUE", "ewf.start-queue", "Coda degli avvii", "every 30s",
        "Europe/Rome", true, Map.of("batch", "50"));   // crea se manca, non sovrascrive mai
...
s.stop();
```

Un job:

```java
public final class MyJob implements Job {
    public JobResult run(JobContext ctx) throws Exception {
        int batch = Integer.parseInt(ctx.param("batch", "100"));
        ...
        if (ctx.stopRequested()) return JobResult.warning("fermato a metà, riprende al giro dopo");
        return JobResult.ok(null).with("letti", n);
    }
}
```

- `ensure` non tocca un job già esistente: le modifiche dell'amministratore restano.
- `runNow(code)` lo fa partire al prossimo giro.
- `setEnabled(code, false)` lo spegne.
- Il job di sistema `eone.connect.purge-runs` cancella lo storico più vecchio di `days` giorni (default 30), ogni notte alle 03:17.
- Il nome del nodo (colonna `node` dello storico) viene dalla variabile `EONE_NODE`; se manca, è `host/pid`.

## Mail: coda di uscita e invio

Chi deve mandare una mail la **accoda**; il job `eone.mail-send` la spedisce.

```java
// nella transazione di chi genera la mail: se questa va in rollback, la mail non esiste
MailOutbox.enqueue(connection, MailMessage.of("ewf", "rossi@cliente.it", "Oggetto", "Testo")
        .withHtml("<p>Testo</p>").withKey("task 123", "ewf-task-123-ROSSI"));
```

- **Chiave anti-doppione** (`dedupKey`): un secondo accodamento con la stessa chiave non produce una seconda mail.
- **Tentativi**: errore temporaneo (rete, 4xx) → si ritenta dopo 1, 5, 15 minuti, 1, 4, 12 ore; poi `DEAD`.
  Errore permanente (5xx: destinatario rifiutato, autenticazione negata) → subito `DEAD`. `retry(id)` la rimette in coda.
- **Invio rimasto appeso** (istanza caduta durante l'invio): ripreso dopo 15 minuti. In quel raro caso la mail
  può partire due volte, mai zero.
- Le mail spedite si cancellano dopo `keepDays` giorni (parametro del job, default 30).

Invio con **Jakarta Mail** (implementazione Angus Mail, `SmtpTransport`): STARTTLS o SSL, AUTH PLAIN/LOGIN,
**OAuth2 (XOAUTH2)**. Con STARTTLS la cifratura è obbligatoria e il certificato deve essere del server.
Su SAP BTP Cloud Foundry, verso un server di posta on-premise, si passa a `SmtpTransport` la socket factory del
servizio di connettività (Cloud Connector). Configurazione da variabili d'ambiente:

| Variabile | Default | |
|---|---|---|
| `EONE_SMTP_HOST` | | obbligatoria |
| `EONE_SMTP_PORT` | 587 (465 con SSL) | |
| `EONE_SMTP_SECURITY` | `STARTTLS` | `NONE`, `STARTTLS`, `SSL` |
| `EONE_SMTP_AUTH` | `PASSWORD` se c'è l'utente, altrimenti `NONE` | `NONE`, `PASSWORD`, `OAUTH2` |
| `EONE_SMTP_USER`, `EONE_SMTP_PASSWORD` | | utente/password, o casella mittente per OAuth2 |
| `EONE_SMTP_FROM` | `EONE_SMTP_USER` | es. `eWF <ewf@cliente.it>` |
| `EONE_SMTP_OAUTH_TENANT`, `_CLIENT_ID`, `_CLIENT_SECRET` | | solo `OAUTH2` (Microsoft 365) |
| `EONE_SMTP_OAUTH_SCOPE` | `https://outlook.office365.com/.default` | |

Esempi: relay aziendale `HOST`, `PORT=25`, `SECURITY=NONE`, `AUTH=NONE`;
Microsoft 365 `HOST=smtp.office365.com`, `AUTH=OAUTH2`, `USER=` casella mittente, più tenant, client id e segreto
di un'applicazione registrata in Entra ID con il permesso SMTP.SendAsApp e accesso alla casella.
Con Microsoft 365, l'autenticazione con utente e password (SMTP AUTH Basic) è in dismissione: per i clienti su
Exchange Online conviene partire direttamente con OAuth2.

## Tabelle (schema `eone_connect`)

- `job` — definizione, prossima esecuzione, ultimo giro a vuoto
- `job_run` — storico
- `sync_state` — watermark per sistema/entità
- `mail_outbox` — coda di uscita delle mail (versione 2 dello schema)
- `schema_version` — versioni installate

## Test

I test su PostgreSQL usano un DB dedicato (lo schema viene cancellato e ricreato):
`CONNECT_TEST_DB_URL`, `CONNECT_TEST_DB_USER`, `CONNECT_TEST_DB_PASSWORD`.
Senza queste variabili i test vengono saltati.
`SmtpTransportTest` prova l'invio vero contro un server SMTP di prova interno al test (STARTTLS con certificato
di prova, PLAIN, LOGIN, XOAUTH2, errori permanenti e temporanei): non richiede rete né server esterni.
Fra i test, due scheduler sullo stesso DB fanno partire lo stesso job nello stesso istante: deve risultare una sola esecuzione.
