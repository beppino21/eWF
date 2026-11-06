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
   al massimo tre istruzioni: `SkipStep`, `SkipTask`, `SetValue`.
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
2. `\c ewf_test`, `SET ROLE ewf_owner;`, `\i '.../db/ewf_schema_v0.6.sql'`
3. variabili d'ambiente (in Eclipse: *Run Configurations → scheda Environment*):
   `EWF_TEST_DB_URL=jdbc:postgresql://localhost:5432/ewf_test`,
   `EWF_TEST_DB_USER=ewf_owner`, `EWF_TEST_DB_PASSWORD=...`

## Database

Cartella `db/`:
- `ewf_schema_v0.6.sql` – schema completo per un DB nuovo;
- `ewf_migrate_0.4_to_0.5.sql`, `ewf_migrate_0.5_to_0.6.sql` – migrazioni incrementali.

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
