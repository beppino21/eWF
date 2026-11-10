# eWF Engine

Motore di workflow approvativo eWF come libreria Java senza dipendenze
(né UI, né SAP, né DB). UI CaptainCasa, connettore SAP e persistenza
PostgreSQL si collegano tramite le interfacce di `spi`.

## Build

```bash
mvn test        # compila e lancia i test
mvn package     # produce target/ewf-engine-0.1.0-SNAPSHOT.jar
```

Il progetto è compilato con `release 21`: il JAR gira su Java 21 e successivi
(anche 25). Per vincolarlo a 25, cambia `maven.compiler.release` nel `pom.xml`.

## Struttura

| Package | Contenuto |
|---|---|
| `engine` | `WorkflowEngine` (operazioni), `Requests` (richieste/risposte), `EngineException` (codici d'errore stabili), `EngineConfig` |
| `engine.model` | Definizioni immutabili (`ProcessType`, `ProcessVersion`, `StepDef`, `TaskDef`, `HookDef`) e runtime (`ProcessInstance`, `TaskInstance`, `HandlerExecution`, ...) |
| `engine.spi` | Interfacce da implementare: store, utenti, risolutore, handler, adapter documento, audit, transazioni |
| `engine.assign` | `RuleBasedAssignmentResolver`: generalizzazione della cascata di `/EWF/WF_USER_TASK` |
| `engine.memory` | Implementazioni in memoria per test e demo |
| `engine.jdbc` | Implementazioni PostgreSQL (solo `java.sql`, nessuna libreria): transazioni, definizioni, runtime, utenti, regole, audit; writer per definizioni e organizzazione; `JdbcEngineFactory` |

## Operazioni

| Metodo | Equivalente /EWF/ |
|---|---|
| `start` | `WF_START` |
| `act` (OK / KO) | `WF_ACTION` + `WF_NEXT_STEP` |
| `choose` / `options` / `choices` | nuovo (nodi di scelta, ex `WF_SELEZIONE_OPZIONE` + processi "fittizi") |
| `move` | `WF_NEXT_STEP` con azione MOVE |
| `claim` / `release` | nuovo (task di team) |
| `retryCompletion` | nuovo (fine processo fallita) |
| `resolveUnknown` | nuovo (esito handler sconosciuto) |
| `restart` | `WF_RESTART_PROCESS` |
| `cancel` | annullamento processo |
| `inbox` | `WEB_TASK_PER_USER` (con sostituti e team) |
| `preview` | nuovo (catena prevista, secondo `preview_mode`) |

## Regole del motore

1. Lo stato lo cambia solo il motore. Gli handler restituiscono esito, documenti e
   al massimo quattro istruzioni: `SkipStep`, `SkipTask`, `SetValue`, `SelectOption`.
2. Gli handler non vengono mai chiamati dentro una transazione: il task passa a
   `EXECUTING`, si chiamano gli handler, poi si applica l'esito.
3. Un'eccezione dell'handler vale `UNKNOWN`: il processo va in `ERROR` e il task resta
   `EXECUTING` finché un amministratore non conferma l'esito (`resolveUnknown`).
4. Il processo si chiude come `COMPLETED` solo **dopo** che l'azione di fine processo
   (es. rilascio OdA) è riuscita; altrimenti va in `ERROR` e si può ritentare.
5. Un processo resta sulla versione di definizione con cui è partito.
6. Può agire sul task: il titolare, un suo sostituto attivo, un membro del team del task;
   mai se il task è in carico a un altro; per OK/KO serve peso effettivo ≥ peso del task.
7. Assegnatario non trovato, non mappato o non attivo → team di riserva
   (`EngineConfig.fallbackTeamCode`) + evento di audit. Nessun task resta senza titolare.

## Documento: lettura all'avvio

Per ogni tipo documento un `DocumentAdapter` (on-premise: `SapDocumentAdapter` → `/EWF/DOC_INFO`)
dice se il documento esiste, quanto vale e i suoi attributi (divisione, societa', gruppo acquisti...).

- Il motore lo legge **solo all'avvio** e salva valore e contesto sul processo (`doc_context`):
  soglie e regole usano quella fotografia, senza richiamare SAP a ogni passo.
- Documento inesistente o cancellato → avvio rifiutato (`DOCUMENT_NOT_FOUND`).
  Sistema non raggiungibile → avvio rifiutato (`BACKEND_UNAVAILABLE`): nessun processo "alla cieca".
- Un documento modificato in modo rilevante durante l'approvazione si gestisce con il **riavvio**,
  che rilegge il documento *prima* di annullare il processo in corso.
- Senza adapter per il tipo documento il processo parte senza verifica (audit `documentCheck=NO_ADAPTER`).

## Avvio automatico: la coda degli avvii

Il sistema di origine scrive in una **coda** cosa è successo a un documento; eWF la legge
(`StartSource`), elabora ogni evento (`WorkflowEngine.onDocumentEvent`) e conferma l'esito.
On-premise la coda è `/EWF/START_Q`, scritta dalle exit di salvataggio **nella LUW del documento**;
sulla Public Edition la scrive l'handler locale degli eventi di business. `StartQueueProcessor.runOnce()`
fa un giro; la frequenza la decide l'applicazione.

| Evento | Chi lo scrive | Cosa fa eWF |
|---|---|---|
| `CREATED` | documento nuovo | regole di avvio → avvia il processo (niente se già aperto o se nessuna regola si applica) |
| `RESET` | la strategia di rilascio ha azzerato il rilascio | riavvia i processi aperti (o avvia, se non ce ne sono) |
| `CHANGED` | modifica senza strategia di rilascio | rilegge il documento e lo confronta **posizione per posizione** con l'avvio: la prima posizione aggiunta, eliminata o con quantità/valore oltre `change_tolerance_pct` → riavvio; altrimenti nulla. Mai sui totali: variazioni opposte su posizioni diverse si annullerebbero |
| `DELETED` | cancellazione | annulla i processi aperti (`DOCUMENT_DELETED`) |

- **Regole di avvio** (`start_rule`): tipo documento + condizioni sugli attributi di `DOC_INFO`
  (`*` = qualsiasi) → tipo processo. Vince la priorità più alta, poi la regola più specifica.
- **Richiedente**: l'utente SAP che ha salvato, tradotto con `sap_user`. Se non ha un corrispondente
  attivo il processo parte comunque, i task del richiedente vanno al team di riserva, l'esito è `WARNING`.
- Un evento in errore (es. SAP irraggiungibile) resta in coda e si ritenta; gli eventi successivi dello
  **stesso documento** aspettano dietro di lui, gli altri documenti proseguono.

## Nodi di scelta e rami

Azioni sul task: **Approva**, **Respingi**, **Sposta**. Un task con opzioni è un **nodo di scelta**:
Approva e Respingi non sono disponibili e l'utente deve scegliere un'opzione (`choose`); Sposta resta.

- La definizione elenca tutti i rami: lo step N può esistere nel **tronco comune** (`option` vuota)
  e in **varianti** legate a un'opzione. Arrivato allo step N, il motore usa la variante di
  un'opzione scelta, altrimenti il tronco. I rami si ricongiungono puntando a uno step del tronco.
- Ogni opzione prosegue come un'approvazione (next OK) o, se `reject`, come un rifiuto
  (next KO; vuoto = fine negativa). Il rifiuto in un nodo è quindi un'opzione come le altre.
- Le **scelte del processo** sono una per nodo: l'opzione dell'ultimo task chiuso su quel nodo.
  Ripassando dal nodo, la nuova scelta sostituisce la precedente. I codici opzione sono univoci
  nella versione.
- Gli handler ricevono le scelte nei parametri: `EWF_OPTION` (opzione del task in corso),
  `EWF_OPTIONS` (`20=RESO;40=CONT`). L'opzione può avere un handler proprio, eseguito alla scelta
  prima degli agganci ON_OK/ON_KO del task.
- **Instradamento automatico**: un handler `ON_ACTIVATE` del nodo restituisce `SelectOption(codice)`
  (da SAP: `SELECT_OPTION=<codice>` in `ET_OUT`) e il nodo si decide da solo, con lo stesso percorso
  di una scelta dell'utente. Un codice non previsto viene ignorato (audit) e il nodo aspetta l'utente.
- Vincolo: un nodo di scelta ha **un solo task**.
- **Validazione all'attivazione**: il motore percorre tutte le combinazioni di scelte e rifiuta la
  versione se un ramo porta a uno step inesistente o se due varianti si sovrappongono; avvisa per
  varianti mai raggiunte. Soglie d'importo e salti degli handler non sono considerati (possono solo
  accorciare un percorso).
- `preview` si ferma al primo nodo non ancora deciso.

## Opzioni che avviano un processo collegato

Un'opzione può indicare un **tipo processo da avviare sullo stesso documento**:

- **HANDOFF** (passaggio di consegne): il processo corrente si chiude come `FORWARDED` (non è
  un'approvazione: nessuna azione di fine) e parte il nuovo. Caso tipico: il processo di sola
  "Avvio", in cui l'autore sceglie quale approvazione far partire o torna al documento.
- **PARALLEL**: il processo corrente prosegue per la sua strada e il nuovo vive accanto.

Regole:
- prima di eseguire qualunque cosa il motore verifica che il nuovo processo possa partire
  (tipo attivo, documento leggibile, nessun processo aperto dello stesso tipo): se no, la scelta
  è rifiutata con il motivo e il nodo resta aperto;
- il richiedente del nuovo processo è quello del processo corrente; chi ha scelto resta
  nel collegamento (`process_link`: `HO` / `PA`) e nell'audit (`PROCESS_SPAWNED`, `PROCESS_FORWARDED`);
- `optionPreview(taskId, opzione)` mostra la catena di approvazione che l'opzione avvierebbe,
  senza avviare nulla; `previewStart(tipo, documento, richiedente)` fa lo stesso per qualsiasi processo;
- il sottoprocesso "in attesa" (il padre aspetta l'esito del figlio) non c'è ancora.

## Test

`WorkflowEngineTest` riproduce il processo `EONE_ODA` del sistema di riferimento
(4 step, soglie a 10.000 e 50.000 EUR, rifiuto che torna al richiedente, rilascio
a fine processo) e i casi emersi dall'analisi di `WF_NEXT_STEP`: paralleli generati
dall'assegnazione, sostituti, team con presa in carico, pesi per processo, skip su
istruzione, rilascio fallito, esito sconosciuto, sposta, doppio click, duplicati,
versioni, riavvio, anteprima.

## Uso su PostgreSQL

```java
DataSource ds = ...;   // in Tomcat: JNDI del container; per prove: new SimpleDataSource(url, user, pwd)
WorkflowEngine engine = JdbcEngineFactory.create(ds, handlers, adapters, currency,
        Clock.systemDefaultZone(), EngineConfig.defaults());
```

L'applicazione si collega con `ewf_app`. Il team di riserva (`EngineConfig.fallbackTeamCode`,
default `WFADM`) deve esistere in `ewf_org.team` con almeno un membro.

Concorrenza su DB:
- la chiusura di un task blocca la riga del processo (`SELECT ... FOR UPDATE`): due paralleli
  approvati nello stesso istante si mettono in fila e il processo avanza una volta sola;
- lock ottimistico su `row_version`: doppio click o due utenti sullo stesso task → `CONCURRENT_UPDATE`;
- vincolo unico sul processo aperto per documento: due avvii simultanei → `DUPLICATE_PROCESS`.

## Test su PostgreSQL

Gli scenari girano due volte: `InMemoryWorkflowEngineTest` e `PostgresWorkflowEngineTest`
(più 4 test che solo un DB vero può mostrare: rollback, approvazioni simultanee, avvii simultanei,
paralleli chiusi insieme). Senza configurazione i test PostgreSQL vengono **saltati**, non falliscono.

Per attivarli serve un database **di test** (ogni test svuota le tabelle `ewf_*`: mai usare `ewf`):

1. in psql come `postgres`: `CREATE DATABASE ewf_test OWNER ewf_owner ENCODING 'UTF8' TEMPLATE template0;`
2. `\c ewf_test`, `SET ROLE ewf_owner;`, `\i '.../db/ewf_schema_v0.12.sql'`
   (DB di test gia' esistente: applicare le migrazioni mancanti, es. `ewf_migrate_0.11_to_0.12.sql`)
3. variabili d'ambiente (in Eclipse: *Run Configurations → scheda Environment*):
   `EWF_TEST_DB_URL=jdbc:postgresql://localhost:5432/ewf_test`,
   `EWF_TEST_DB_USER=ewf_owner`, `EWF_TEST_DB_PASSWORD=...`

## Database

Cartella `db/`:
- `ewf_schema_v0.12.sql` – schema completo per un DB nuovo;
- `ewf_migrate_X_to_Y.sql` – migrazioni incrementali (0.4 → 0.12), da applicare in ordine.

## Limiti noti

- `LocalTransactions` (in memoria) non fa rollback: va bene solo per test e demo.
- Il controllo di budget di `WF_USER_TASK` (ordinato annuo per fornitore) non è
  ancora implementato: andrà come filtro fornito dall'adapter del tipo documento.
- `preview` segue solo il percorso di approvazione; per gli assegnatari determinati
  da handler indica "determinato all'attivazione" (non chiama l'handler).
- Un `ON_ACTIVATE` fallito (es. mail) non blocca il processo: resta in audit.
- `JdbcDefinitionStore` tiene in cache le versioni (immutabili una volta attive); se si modifica
  a mano una versione già usata, chiamare `evict(id)`.
- L'audit non calcola ancora la catena di hash (`prev_hash`/`hash` predisposti nello schema).
