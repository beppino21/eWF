# eWF SAP connector

Collega il motore eWF a SAP: ogni handler ABAP viene eseguito tramite la FM RFC
`/EWF/ENGINE_DISPATCH` (vedi `ewf_handler_contract.md`).

| Classe | Ruolo |
|---|---|
| `JcoDispatcherHandler` | Handler del motore che chiama `/EWF/ENGINE_DISPATCH` per una FM |
| `SapHandlerRegistry` | Fornisce un `JcoDispatcherHandler` per i codici che sono FM ABAP (`/EWF/...`, `Z...`); `chain(...)` combina più registri |
| `SapDestinations` | Destinazioni JCo da file esterni, password da variabile d'ambiente |
| `DispatcherProbe` | Prova a riga di comando: esegue un handler due volte con la stessa chiave |
| `migration.MigrationTool` | Estrattore: configurazione `/EWF/` → DB eWF, con rapporto HTML |

## Configurazione della destinazione (niente password nel codice)

1. Crea una cartella **fuori** dal progetto e da Git, es. `C:\ewf-config`.
2. Copia `config/EWF_DEV.properties.example` in `C:\ewf-config\EWF_DEV.properties`
   e compila host, istanza, mandante, utente RFC.
3. Variabili d'ambiente:
   - `EWF_SAP_CONFIG_DIR=C:\ewf-config`
   - `EWF_SAP_EWF_DEV_PASSWD=<password dell'utente RFC>`

Se nel file `.properties` c'è una password, il connettore si rifiuta di partire.

## Prova da Eclipse (Windows)

1. Sul progetto `ewf-engine`: *Run As → Maven install* (pubblica il motore nel repository
   locale, così `ewf-sap` lo trova).
2. Importa `ewf-sap` (*Existing Maven Projects*), poi Alt+F5.
3. Clic destro su `DispatcherProbe.java` → *Run As → Run Configurations…* → *Java Application* → nuova:
   - **Arguments → Program arguments**: `EWF_DEV /EWF/WF_DUMMY 4500000001`
   - **Arguments → VM arguments**: `-Djava.library.path=C:\percorso\cartella\di\sapjco3.dll`
   - **Environment**: le due variabili sopra.
4. *Run*. Output atteso:

```
--- 1a chiamata
esito:      OK
--- 2a chiamata (stessa chiave)
esito:      OK  (dal registro, non rieseguita)
```

Se JCo segnala *"Illegal JCo archive"* (il jar nel repository Maven è rinominato in
`sapjco3-3.1.12.jar` e JCo 3.1 accetta solo il nome originale): in *Run Configurations →
Dependencies → Classpath Entries → Add External JARs* aggiungi il `sapjco3.jar` originale
e portalo in cima con *Up*.

Altre prove utili:

| Argomenti | Atteso |
|---|---|
| `EWF_DEV /EWF/WF_CHECK_LOCK_EKKO <OdA>` | KO finché la FM rilegge TASKRUN dal DB; OK dopo il ritocco |
| idem, con l'OdA aperto in modifica in ME22N | KO "Ordine di Acquisto in uso" |
| `EWF_DEV /EWF/NON_ESISTE 1` | KO "Handler inesistente" |
| `EWF_DEV /EWF/WF_RIL_ODA <OdA> F Z_WF_SET_RIL=01` | il codice di rilascio arriva in `I_TASKRUN-Z_WF_SET_RIL` |

## Parametri degli agganci

Nella `config` di un aggancio (`task_hook.config`):
- chiave = nome di un campo di `/EWF/TASKRUN` (es. `Z_WF_SET_RIL`) → valorizza quel campo
  di `I_TASKRUN`: le FM legacy lo leggono come oggi;
- altre chiavi → `IT_PARAMS`: il dispatcher le passa al parametro omonimo della FM
  (es. `I_AUART`), convertite nel tipo dichiarato.

## Esiti

| Situazione | Esito per il motore |
|---|---|
| SAP non raggiungibile / dispatcher assente (prima della chiamata) | KO (si può riprovare) |
| Errore durante la chiamata (rete, dump) | UNKNOWN (va verificato) |
| `E_STATUS` O / K / U | OK / KO / UNKNOWN |

## Estrattore di migrazione (`migration.MigrationTool`)

Legge la configurazione `/EWF/` tramite la FM RFC `/EWF/ENGINE_EXPORT` e la porta nel DB eWF.
Non porta processi in corso né storico (restano su `/EWF/`), né password.

```
MigrationTool <destinazione SAP> <url JDBC> [--apply] [--exclude=P1,P2] [--report=cartella]
```

- **senza `--apply`: prova generale.** Scrive, valida e attiva tutto in una transazione e poi la annulla:
  il DB resta com'era, ma il rapporto riflette i controlli veri del DB.
- **`--apply`**: scrive davvero, in un'unica transazione (o tutto o niente). Richiede un DB **senza processi
  avviati**: svuota definizioni e organizzazione e le riscrive, quindi si può ripetere finché il rapporto è pulito.
- `--exclude`: processi da non migrare, codici esatti o prefissi con `*` (default `EONE_TRASFERTE,UC_*`:
  trasferte = progetto dedicato, `UC_*` = dimostrazione Università Cattolica).
- `--report`: cartella del rapporto (`migrazione-<dest>-<data>-prova.html` + `.csv` delle osservazioni).

Variabili d'ambiente, oltre a quelle di `SapDestinations`:
`EWF_DB_USER` (default `ewf_owner`: serve il proprietario dello schema) e `EWF_DB_PASSWORD`.

Regole di trasformazione principali (dettaglio in `LegacyMapper`):

| `/EWF/` | Nuovo modello |
|---|---|
| `STEP_OK/KO` 99999 | fine processo |
| `STEP_OK/KO` 00000 | avviso + errore di validazione: la versione resta in **bozza** |
| `WF_DUMMY` | nessun aggancio |
| `THIS_USER` = `WF_USER_TASK` | regole (o STARTER/ACTOR se il ruolo è assegnato con `SY_START`/`SY_UNAME`) |
| `THIS_USER` = `WF_USER_TASK_MIGO` | STARTER |
| altre FM in `THIS_USER` | handler di assegnazione |
| `Z_WF_SET_RIL` | parametro dell'azione di fine processo (rilascio unico, come oggi) |
| `USER_TASK` | regole, **senza** il criterio team; fascia 0..0 = nessuna fascia |
| `USER0` | utenti (login = UNAME, utente SAP = UNAME_SAP o UNAME), senza password |


## Prova del motore su un OdA: `OdaProbe`

Motore vero (DB `ewf` + dispatcher SAP), nessuna UI. Argomenti del programma:

| Comando | Effetto |
|---|---|
| `avvia <ebeln> <login>` | avvia `EONE_ODA` sull'ordine |
| `stato <ebeln>` | processi, task, chiamate SAP, prossimi step |
| `ok <taskId> <login> [nota]` | approva |
| `ko <taskId> <login> <nota>` | respingi |
| `sposta <taskId> <login> <login dest>` | sposta |
| `riprova <processId> <login>` | ritenta l'azione di fine processo (processo in `ERROR`) |
| `coda` | un giro della coda degli avvii `/EWF/START_Q` |
| `servizio` | scheduler eone-connect: la coda degli avvii ogni 30 secondi, finché non lo si ferma |
| `lavori [codice]` | job pianificati, prossima esecuzione e ultime esecuzioni |
| `regola <tipodoc> <processo> [priorità] [chiave=valore …]` | nuova regola di avvio (es. `regola F EONE_ODA`) |
| `regole <tipodoc>` | elenca le regole di avvio |

Variabili: `EWF_DB_URL` (default `jdbc:postgresql://localhost:5432/ewf`), `EWF_DB_USER` (default `ewf_app`),
`EWF_DB_PASSWORD`, `EWF_SAP_DEST` (default `EWF_DEV`) e quelle della destinazione SAP.

## Adattatore documento: `/EWF/DOC_INFO`

FM RFC in `abap/EWF_DOC_INFO.abap` (stesso gruppo funzioni del dispatcher). Usata da `SapDocumentAdapter`
all'avvio di ogni processo: esistenza, valore (divisa standard del mandante, via `/EWF/WF_DOCVALUE`),
attributi in `ET_CONTEXT` (`WERKS`, `BUKRS`, `EKGRP`, `EKORG`, `LGORT`, `LIFNR`, `BSART`).
Per le regole valgono gli stessi attributi del vecchio `WF_USER_TASK`: OdA → divisione e societa';
RdA → anche gruppo acquisti e magazzino. Gli altri restano nel contesto con prefisso `sap.`.

## Coda degli avvii: `/EWF/START_Q`

Oggetti in `abap/`: `EWF_START_Q_DDIC.md` (tabella e struttura), `EWF_START_Q_WRITE.abap` (modulo di
aggiornamento per le exit), `EWF_START_Q_READ.abap` e `EWF_START_Q_CONFIRM.abap` (RFC, usate da
`SapStartQueue`), `ZCL_EWF_PO_START_EXAMPLE.abap` (esempio di BAdI `ME_PROCESS_PO_CUST~POST`).
Le exit chiamano `/EWF/START_Q_WRITE IN UPDATE TASK` al posto di `/EWF/WF_START`.
Una nuova migrazione (`--apply`) conserva le regole di avvio dei processi che esistono ancora.

## Avvio automatico: job `ewf.start-queue` (eone-connect)

La coda degli avvii viene letta dallo scheduler di eone-connect: job `EWF_START_QUEUE`, ogni 30 secondi.
Con più Tomcat accesi gira su uno solo.

1. `mvn install` di `eone-connect`, poi di `ewf-engine`, poi build di `ewf-sap`.
2. Schema `eone_connect` nel DB `ewf`, con l'utente proprietario (`CONNECT_DB_PASSWORD` nell'ambiente):
   `it.eone.connect.db.ConnectSchemaTool jdbc:postgresql://localhost:5432/ewf ewf_owner ewf_app`
3. `OdaProbe servizio`: al primo avvio crea il job. Pianificazione e parametri si cambiano in `eone_connect.job` senza riavvio:
   - `batch` — eventi per giro (50)
   - `maxRounds` — giri consecutivi se la coda è piena (10)
   - `attempts` — tentativi SAP (20)

Esiti nello storico:

| Esito | Quando |
|---|---|
| (nessuna riga) | coda vuota; aggiorna solo `last_idle_at` |
| `OK` | tutto avviato |
| `WARNING` | almeno un avviso, es. task al team di riserva |
| `ERROR` | almeno un evento fallito; gli eventi successivi dello stesso documento restano in coda |

`OdaProbe lavori` mostra lo stato.
