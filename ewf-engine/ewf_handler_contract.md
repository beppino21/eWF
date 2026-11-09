# eWF – Contratto degli handler (v0.1)

Il motore Java decide **cosa** succede (stati, transizioni, assegnazioni, audit).
Gli handler fanno **una cosa** sul backend e restituiscono un esito.
Un handler non modifica mai lo stato del motore: al massimo restituisce
*istruzioni* che il motore valida, applica e registra.

## 1. Tipi di handler

| Contratto | Quando | Input specifico | Output |
|---|---|---|---|
| `TASK_ACTION` | Eventi task: `BEFORE_ACTION`, `ON_OK`, `ON_KO`, `ON_ACTIVATE`, `BUTTON` | azione (`OK`/`KO`/`BUTTON`), parametri dell'aggancio | esito, messaggio, documenti creati, istruzioni |
| `ASSIGNEE` | Attivazione di un task con `assignee_mode = HANDLER` | — | uno o più utenti (più utenti = task paralleli) |
| `PROCESS_END` | Processo in stato `COMPLETING` | esito finale (`APPROVED`/`REFUSED`) | esito, messaggio, documenti creati |
| `DOC_ADAPTER` | Avvio, avanzamento, risolutore regole, UI | chiave documento | valore e divisa, attributi di contesto, link di visualizzazione |

Tipi di implementazione (`handler.kind`): `RFC_FM` (via dispatcher), `API` (OData/REST,
Public Edition), `JAVA` (classe nel prodotto o in un modulo), `INTERACTIVE`
(deep link + verifica ex-post).

## 2. Richiesta (comune)

```text
idempotencyKey   uuid      una per esecuzione; ripetere la chiamata con la stessa chiave
                           NON riesegue l'azione
contract, event, action
process          id, processType, docType, documentKey, backend, correlationKey
task             id, stepNo, subopNo, role, taskParam (ex ZWF_TASK_PARAM)
actor            login eWF, utente SAP mappato (se esiste), acting-as-substitute
params           mappa chiave/valore da task_hook.config (es. release_code=01, I_AUART=ZRE)
```

## 3. Risposta (comune)

```text
status           OK | KO | UNKNOWN
message          testo per l'utente (ex E_RETURN)
documents[]      docCat, docNumber, docYear, companyCode   -> generated_document
users[]          idType (EWF | SAP), userId                -> solo ASSIGNEE
directives[]     istruzioni al motore (vedi §4)
output{}         altri valori restituiti (visibili in audit)
replayed         true se l'esito arriva dal registro di idempotenza
```

Semantica:

- **OK**: il motore prosegue (avanza, chiude, assegna).
- **KO**: il motore non avanza. Per `BEFORE_ACTION` l'azione dell'utente è bloccata
  con il messaggio; per `ON_OK`/`ON_KO` il task resta aperto; per `PROCESS_END` il
  processo va in `ERROR` (non `COMPLETED`) e si può ritentare.
- **UNKNOWN**: esecuzione precedente interrotta (timeout, dump, rete). Il motore mette
  l'esecuzione in `UNKNOWN` e il processo in `ERROR`; serve una verifica (automatica
  se l'handler la prevede, altrimenti manuale da cruscotto amministratore).

## 4. Istruzioni (directives)

| Istruzione | Effetto | Contratti |
|---|---|---|
| `SKIP_STEP=<n>` | lo step n viene saltato (`instance_step_state.reason = HANDLER`) | TASK_ACTION |
| `SKIP_TASK=X` | il task appena creato viene chiuso come SKIPPED | TASK_ACTION (`ON_ACTIVATE`) |
| `SET_VALUE=<importo>/<divisa>` | aggiorna il valore documento del processo | TASK_ACTION, DOC_ADAPTER |
| `SELECT_OPTION=<codice>` | sceglie l'opzione di un nodo di scelta al posto dell'utente | TASK_ACTION (`ON_ACTIVATE` del nodo) |

Niente altro: niente "vai allo step X", niente modifiche al piano oltre lo skip.
Le transizioni restano quelle della definizione versionata: anche `SELECT_OPTION`
sceglie solo tra i rami gia' previsti.

Parametri in ingresso legati alle scelte (in `IT_PARAMS`): `EWF_OPTION` = opzione scelta
sul task in corso; `EWF_OPTIONS` = scelte gia' registrate nel processo, `nodo=opzione`
separate da `;` (es. `20=RESO;40=CONT`).

## 5. Assegnazione: identificativi

Un handler `ASSIGNEE` dichiara il tipo di ogni utente restituito:

- `EWF` – login eWF (gli handler legacy restituiscono `USER0-UNAME`, che dopo la
  migrazione è il login eWF);
- `SAP` – utente SAP (es. responsabile del CdC): il motore lo converte con
  `app_user.sap_user`.

Utente non trovato o non attivo → assegnazione di riserva (team amministratori
workflow) + evento di audit `ASSIGNEE_FALLBACK`. Il task non resta mai senza titolare.

## 6. Dispatcher RFC `/EWF/ENGINE_DISPATCH`

Un'unica FM RFC chiama qualsiasi handler ABAP. Il motore passa `I_TASKRUN`
(struttura `/EWF/TASKRUN` valorizzata da Java) così le FM legacy funzionano
**senza modifiche di firma**.

- Lega solo i parametri dichiarati dalla FM (letti da `FUPARAREF`).
- `I_UCOMM` / `I_AZIONE` ← azione; altri parametri importing ← `IT_PARAMS`,
  convertiti nel tipo dichiarato (es. `I_AUART TYPE AUART`).
- Uscite riconosciute: `E_ERROR`, `E_RETURN`, `E_USER`, `TB_USERS`, `E_VBELN`,
  `E_SKIP`; le altre uscite semplici finiscono in `ET_OUT`.
- Documenti: anche quelli scritti dalla FM in `/EWF/TASKDOCUM` e `/EWF/TASK_DOCUM`
  per procid/taskid.
- `MESSAGE TYPE 'E'` della FM → KO con il testo del messaggio.
- KO → `ROLLBACK WORK` delle modifiche non ancora committate (`I_ROLLBACK_ON_KO`).
- Idempotenza su `/EWF/ENG_DISP`: riga `R` + `COMMIT` prima della chiamata, esito
  dopo. Stessa chiave con esito registrato → restituisce quello (`E_REPLAYED = X`);
  riga ancora `R` → `E_STATUS = U`.

Limite non aggirabile: FM con popup, dynpro o `CALL TRANSACTION ... MODE 'A'`
non girano via RFC senza GUI. Vanno classificate `INTERACTIVE`.

### Oggetti DDIC da creare

| Oggetto | Tipo | Campi |
|---|---|---|
| `/EWF/S_ENG_KV` | struttura | `NAME` CHAR30, `VALUE` CHAR255 |
| `/EWF/S_ENG_USER` | struttura | `ID_TYPE` CHAR3, `USER_ID` CHAR64 |
| `/EWF/S_ENG_DOC` | struttura | `DOC_CAT` CHAR4, `DOC_NUMBER` CHAR20, `DOC_YEAR` CHAR4, `COMP_CODE` CHAR4 |
| `/EWF/ENG_DISP` | tabella trasparente (classe A) | `MANDT` (chiave), `IDEMP_KEY` CHAR36 (chiave), `HANDLER_FM` CHAR30, `CONTRACT` CHAR15, `EVENT` CHAR15, `PROCID` NUMC10, `TASKID` NUMC10, `ACTOR` CHAR64, `STATUS` CHAR1, `MESSAGE` CHAR255, `TS_START` DEC15 (TIMESTAMP), `TS_END` DEC15, `OUT_DATA` STRING |

Nel TOP del gruppo funzioni: `TYPE-POOLS abap.`

## 7. Handler legacy da ritoccare

Leggono di nuovo `/EWF/TASKRUN` dal DB solo per riottenere la riga che hanno già in
`I_TASKRUN`. Con il motore esterno quella riga in SAP non esiste: basta usare
`I_TASKRUN` (modifica di una riga ciascuno).

`WF_CHECK_LOCK_EKKO`, `WF_CHECK_LOCK_EBAN`, `WF_RIL_ODA`, `WF_RIL_RDA`, `WF_OK_IBAN`.

In più `WF_RIL_ODA`: controllare la `RETURN` della BAPI e valorizzare `E_ERROR`
(oggi un rilascio fallito risulta OK) e leggere il codice di rilascio dai parametri
dell'aggancio (`release_code`) invece che da `Z_WF_SET_RIL`.

## 8. Interfacce Java (bozza)

```java
public interface Handler<C extends HandlerContext, R extends HandlerResult> {
    R execute(C context);                       // deve essere idempotente su context.idempotencyKey()
}

public record HandlerContext(UUID idempotencyKey, Contract contract, HookEvent event,
        String action, ProcessRef process, TaskRef task, Actor actor,
        Map<String, String> params) {}

public record HandlerResult(Status status, String message, List<DocRef> documents,
        List<UserRef> users, List<Directive> directives, Map<String, String> output,
        boolean replayed) {
    public enum Status { OK, KO, UNKNOWN }
}

public interface DocumentAdapter {             // uno per tipo documento (F, B, FA, FI_DOC, ...)
    DocKey parse(String documentKey);
    Money value(DocKey key);
    Map<String, String> context(DocKey key);   // purchasing_group, plant, storage_location, ...
    URI displayLink(DocKey key, Channel channel);
}
```

`RfcDispatcherHandler` implementa `Handler` chiamando `/EWF/ENGINE_DISPATCH` via JCo;
`ApiHandler` e le classi `JAVA` implementano la stessa interfaccia. Il motore non sa
quale implementazione sta usando.
