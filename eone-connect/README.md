# eone-connect

Libreria comune e-One per i "punti di contatto" fra le applicazioni e i sistemi esterni (SAP, WMS, …).
Prima versione: **scheduler**, **storico delle esecuzioni**, **stato di sincronizzazione**.
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

## Tabelle (schema `eone_connect`)

- `job` — definizione, prossima esecuzione, ultimo giro a vuoto
- `job_run` — storico
- `sync_state` — watermark per sistema/entità
- `schema_version` — versioni installate

## Test

I test su PostgreSQL usano un DB dedicato (lo schema viene cancellato e ricreato):
`CONNECT_TEST_DB_URL`, `CONNECT_TEST_DB_USER`, `CONNECT_TEST_DB_PASSWORD`.
Senza queste variabili i test vengono saltati.
Fra i test, due scheduler sullo stesso DB fanno partire lo stesso job nello stesso istante: deve risultare una sola esecuzione.
