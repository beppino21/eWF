# ewf-service

Livello di servizio dell'applicazione transazionale eWF: è quello che chiamerà l'interfaccia CaptainCasa.
Non contiene interfaccia utente e non dipende da SAP: le parti specifiche del sistema di origine
(allegati GOS, stampa PDF, collegamento SAP GUI) entrano attraverso interfacce in `it.eone.ewf.service.spi`.

## Prerequisiti

OpenPDF 2.0.3 (`com.github.librepdf:openpdf`) è dichiarato esplicitamente: è la stessa versione portata da
CaptainCasa, così nel WAR non finiscono due versioni della libreria.


1. `mvn install` di **ewf-engine** (contiene `WorkflowEngine.path()`, usato dal dettaglio).
2. Database a **schema 0.14**: `db/ewf_migrate_0.13_to_0.14.sql` di ewf-engine, eseguito come `ewf_owner`.
   La migrazione assegna da sola i permessi a `ewf_app` (se il ruolo esiste).

## Cosa offre

| Area | Metodi | Note |
|---|---|---|
| Login | `AuthService.login`, `changePassword`, `resetPassword` | Utenti eWF; nel DB solo l'hash PBKDF2. Dopo 5 tentativi falliti, blocco di 15 minuti. Password provvisoria con cambio obbligato al primo accesso. |
| Lista | `EwfService.worklist`, `columns` | Task propri, da sostituto, di team. Riepilogo (`process_type.list_summary`), semaforo per giorni di attesa (`sla_yellow_days` / `sla_red_days`, default 1–5 verde, 6–10 giallo, oltre rosso), filtro per tipo di processo e testo, colonne specifiche (`list_columns`). |
| Dettaglio | `detail` | Testata con attributi del documento, percorso (fatto / in corso / previsto / scelta), storico, commenti, task su cui l'utente può agire, con opzioni e funzioni speciali. |
| Azioni | `approve`, `reject`, `choose`, `move`, `findUsers` | Le autorizzazioni le verifica il motore (titolare, sostituto, team). Il rifiuto vuole sempre la motivazione. `rowVersion` evita di sovrascrivere un collega che ha già firmato. I task `SAPGUI_ONLY` non si chiudono dal web. |
| Commenti | `addComment` | Solo aggiunte (`ewf_core.comment`, kind `N`). Le note di OK/KO/sposta restano nello storico del task. |
| Allegati | `attachments`, `attachmentContent`, `addAttachment`, `deleteAttachment` | Secondo `process_type.attachment_scope`: `PROCESS` = in eWF (`ewf_core.attachment` provider `LOCAL` + `attachment_content`), `DOCUMENT` = nel sistema di origine tramite `DocumentAttachments`. Limite di dimensione configurabile (default 20 MB). Si eliminano solo i propri, a processo aperto, e restano nel DB nascosti. |
| Schede PDF | `documentSheet` | Una o più viste per tipo documento (`DocumentSheets`, es. «Scheda OdA» da `BAPI_PO_GETDETAIL1`) più il «Riepilogo approvazione» (`EWF`), sempre disponibile e senza sistema di origine. In coda a ogni scheda: approvazioni, prossimi passi, commenti. Impaginazione unica (`sheet.SheetPdf`, OpenPDF). |
| SAP GUI | `sapGuiShortcut` | Tramite `SapGuiLink` (su SAP: file `.sap` per `/EWF/VIEWDOC`). Solo per utenti con `sap_user`. |
| Funzioni speciali | `runFunction` | Righe di `ewf_core.task_function`: pulsante, handler (contratto `TASK_ACTION`, azione = codice funzione), testo di conferma. Non cambiano lo stato del processo. |

Chi vede un processo: chi l'ha avviato, chi ha o ha avuto un task su quel processo (titolare, chi ha agito,
chi l'ha spostato o ricevuto), i membri dei team dei suoi task, e chi sostituisce oggi una di queste persone.
Ogni metodo lo controlla da sé: l'interfaccia non deve fidarsi di se stessa.

Gli errori sono `ServiceException` con un codice (`AUTH_FAILED`, `NOT_AUTHORIZED`, `INVALID_STATE`, `BACKEND`, …)
e un messaggio già pensato per l'utente.

## Interfacce verso il sistema di origine (`spi`)

| Interfaccia | Implementazioni previste |
|---|---|
| `DocumentAttachments` | GOS via RFC (ECC, S/4HANA on-premise e Private); `API_CV_ATTACHMENT_SRV` via OData (S/4HANA Public Edition); documentale esterno |
| `DocumentSheets` | BAPI via RFC (R/3, S/4HANA on-premise e Private); OData (Public Edition) |
| `SapGuiLink` | file `.sap` con transazione `/EWF/VIEWDOC` |

Ognuna può mancare (`Backends.none()`): la funzione allora non compare nel dettaglio (`Links`).

## Configurare la lista per tipo di processo

```sql
UPDATE ewf_core.process_type
   SET list_summary = '{sap.LIFNR} {supplier_name} - {plant}',
       list_columns = '[{"label":"Fornitore","key":"supplier_name"},{"label":"Divisione","key":"plant"}]',
       sla_yellow_days = 6, sla_red_days = 11,
       attachment_scope = 'PROCESS'
 WHERE code = 'EONE_ODA';
```

I segnaposto prendono gli attributi del documento salvati all'avvio (`doc_context`) più quelli calcolati:
`documentKey`, `processType`, `processText`, `starter`, `value` (formato italiano), `currency`.
Un segnaposto senza valore sparisce, con i separatori rimasti orfani.

## Password provvisoria da riga di comando

Finché l'applicazione non ha la gestione utenti:

```
java -cp ... it.eone.ewf.service.auth.PasswordTool BIANCHI
```

Database da `EWF_DB_URL`, `EWF_DB_USER` (default `ewf_app`), `EWF_DB_PASSWORD` (solo da ambiente).
La password viene stampata una volta sola; al primo accesso verrà chiesta una nuova.

## Prova da console (`ServiceProbe`)

Finché non c'è l'interfaccia CaptainCasa, `it.eone.ewf.service.ServiceProbe` permette di provare login, cambio
della password provvisoria, lista e dettaglio, **in sola lettura** (niente approvazioni: qui mancano gli handler SAP).
Comandi: `lista`, `dettaglio <processo>`, `scheda <processo>` (riepilogo approvazione in PDF, salvato nella
cartella temporanea). Stesse variabili d'ambiente di `PasswordTool`; se manca `EWF_DB_PASSWORD` la chiede. Nella console di Eclipse la password resta visibile mentre la si scrive.

## Test

`ServiceUnitTest` gira sempre. `EwfServicePostgresTest` richiede un database **di test**, perché svuota le
tabelle `ewf_core` / `ewf_org`: `EWF_TEST_DB_URL`, `EWF_TEST_DB_USER` (il proprietario dello schema),
`EWF_TEST_DB_PASSWORD`. Mai il database `ewf` di lavoro.
