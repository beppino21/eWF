# Coda degli avvii eWF

**Il punto di aggancio descrive, il motore decide.**

Quando un documento viene salvato, il punto di aggancio (BAdI, user exit, BTE) chiama l'estrattore del suo
tipo di oggetto e mette in coda una fotografia del documento: testata, attributi, posizioni.
Lo fa nella stessa LUW del salvataggio.
Il motore Java legge la coda, individua il contesto e decide quale processo avviare, riavviare o annullare,
senza tornare a interrogare SAP.

```
salvataggio (BAdI / exit / BTE)
   ├─ estrattore del tipo oggetto (/EWF/CTX_PO, ...)  attributi + posizioni, solo dati in memoria
   └─ /EWF/START_Q_WRITE  IN UPDATE TASK               testata + /EWF/START_QA + /EWF/START_QI

motore Java (job eone-connect ewf.start-queue)
   ├─ /EWF/START_Q_READ     eventi con le loro fotografie
   └─ /EWF/START_Q_CONFIRM  esiti

/EWF/DOC_INFO  stesso estrattore, dati dal DB: lettura su richiesta (avvio manuale, riavvio, riserva)
```

Estendere il contesto di un tipo di oggetto = aggiungere attributi nel suo estrattore.
La coda non cambia: gli attributi sono righe di `/EWF/START_QA`.

---

## Tabella `/EWF/START_Q` — testata dell'evento
Classe di consegna **A**, dipendente dal mandante, categoria di dimensione 2.

| Campo | Chiave | Tipo | Lungh. | Descrizione |
|---|---|---|---|---|
| MANDT | X | MANDT | | |
| QID | X | CHAR | 32 | Id evento (UUID) |
| TIPODOC | | /EWF/WF_TIPODOC | | Tipo di oggetto eWF (F = OdA, B = RdA, ...) |
| DOCUMENT | | /EWF/WF_DOCUMENT | | Chiave documento |
| EVENT | | CHAR | 10 | `TOUCHED` (neutro, default) |
| UNAME | | SYUNAME | | Chi ha salvato |
| TCODE | | SYTCODE | | Transazione del salvataggio |
| IS_NEW | | CHAR | 1 | X = creato in questo salvataggio |
| EXIST | | CHAR | 1 | X = attivo; spazio = cancellato / senza posizioni attive |
| NETWR | | NETWR (CURR 15,2) | | Valore (rif. WAERS) |
| WAERS | | WAERS | | Divisa del valore |
| ENGINE | | CHAR | 1 | `J` Java, `L` ponte legacy (da `/EWF/START_CFG`) |
| CREATED_AT | | TIMESTAMPL | | Quando è nato l'evento |
| STATUS | | CHAR | 1 | ' ' da elaborare, D fatto, W fatto con avviso, E errore (si ritenta) |
| ATTEMPTS | | INT2 | | Tentativi |
| MESSAGE | | CHAR | 255 | Esito |
| PROCID | | CHAR | 20 | Processo coinvolto |
| PROCESSED_AT | | TIMESTAMPL | | Ultima elaborazione |
| TARGET | | CHAR | 10 | solo ponte legacy |
| PROCESSO | | /EWF/WF_PROCESSO | | solo ponte legacy |
| ACTION | | CHAR | 1 | solo ponte legacy: S avvio, R riavvio, N nessuna azione |

Riferimento in SE11: NETWR → `/EWF/START_Q-WAERS`.
Indice secondario `S01`: MANDT, ENGINE, STATUS, CREATED_AT.

## Tabella `/EWF/START_QA` — attributi dell'evento
Classe di consegna **A**, dipendente dal mandante, categoria di dimensione 3.

| Campo | Chiave | Tipo | Lungh. | Descrizione |
|---|---|---|---|---|
| MANDT | X | MANDT | | |
| QID | X | CHAR | 32 | Evento |
| NAME | X | CHAR | 30 | Nome attributo (campo SAP: BUKRS, WERKS, EKGRP, ...) |
| VALUE | | CHAR | 255 | Valore |

## Tabella `/EWF/START_QI` — posizioni dell'evento
Classe di consegna **A**, dipendente dal mandante, categoria di dimensione 3.

| Campo | Chiave | Tipo | Riferimento |
|---|---|---|---|
| MANDT | X | MANDT | |
| QID | X | CHAR 32 | |
| ITEM | X | CHAR 10 | numero posizione |
| MENGE | | MENGE_D (QUAN 13,3) | `/EWF/START_QI-MEINS` |
| MEINS | | MEINS | |
| NETWR | | NETWR (CURR 15,2) | `/EWF/START_QI-WAERS` |
| WAERS | | WAERS | |

Stessi campi di `/EWF/S_DOC_ITEM` (più MANDT e QID): `MOVE-CORRESPONDING` funziona fra le due.

Pulizia: chi cancella righe di `/EWF/START_Q` cancella anche le righe con lo stesso QID in `QA` e `QI`.

## Tabella `/EWF/START_CFG` — chi elabora
Classe di consegna **C**, dipendente dal mandante, SM30.

| Campo | Chiave | Tipo | Lungh. | Descrizione |
|---|---|---|---|---|
| MANDT | X | MANDT | | |
| TIPODOC | X | /EWF/WF_TIPODOC | | |
| ENGINE | | CHAR | 1 | `J` Java, `L` ponte legacy, `N` non accodare |
| MODO | | CHAR | 1 | solo legacy: `P` periodica, `I` immediata dopo COMMIT |
| ROUTER_FM | | RS38L_FNAM | | solo legacy: instradatore (vuoto = `/EWF/START_ROUTE_STD`) |

Tipo documento senza riga = `L`, `P`: comportamento di oggi. Per il motore Java: `F`, `J`.

## Estrattori

| Tipo | FM | Attributi | Posizioni |
|---|---|---|---|
| F (OdA) | `/EWF/CTX_PO` | BUKRS EKORG EKGRP BSART LIFNR WERKS | attive (LOEKZ vuoto): quantità, valore |

Regole per chi scrive un estrattore:
- riceve i dati del documento e non legge dal DB, perché gira dentro il salvataggio dell'utente;
- i nomi degli attributi sono i nomi dei campi SAP: il motore li espone come `sap.<NOME>`;
- lo stesso estrattore serve sia il punto di aggancio (dati in memoria) sia `/EWF/DOC_INFO` (dati dal DB).

## Function module e oggetti

| Oggetto | Tipo | Ruolo |
|---|---|---|
| `/EWF/CTX_PO` | normale | estrattore OdA |
| `/EWF/START_Q_WRITE` | **aggiornamento** (avvio immediato) | accoda testata + attributi + posizioni |
| `/EWF/START_Q_READ` | RFC | motore Java: eventi + `ET_ATTR` + `ET_ITEMS` |
| `/EWF/START_Q_CONFIRM` | RFC | motore Java: esiti |
| `/EWF/DOC_INFO` | RFC | lettura su richiesta (usa gli stessi estrattori) |
| BAdI `ME_PROCESS_PO_CUST~POST` | punto di aggancio OdA | `ME_PROCESS_PO_CUST_QUEUE.abap` |

### Ponte legacy (solo per chi resta sul motore ABAP durante la migrazione)

| Oggetto | Tipo | Ruolo |
|---|---|---|
| `/EWF/START_Q_KICK` | RFC (tRFC) | sveglia: pianifica il job immediato |
| `/EWF/START_Q_JOB` | report | job immediato + periodico (SM36) |
| `/EWF/START_Q_PROCESS` / `_ONE` | normale | elaboratore legacy |
| `/EWF/START_ROUTE_STD` | normale | instradatore standard |
| `/EWF/START_TGT` | tabella C | destinazione → funzione di avvio / riavvio |
| `ponte_legacy_esempi/` | | esempio di instradatore OdA che riproduce la vecchia BAdI POST + CLOSE |

`/EWF/START_TGT`: MANDT, TARGET (CHAR 10, chiave), START_FM, RESTART_FM (RS38L_FNAM), DESCR (CHAR 60).
Riga minima: `LEGACY`, `/EWF/WF_START`, nessun riavvio.

## Struttura `/EWF/S_START_RES` — esito per `/EWF/START_Q_CONFIRM` (invariata)

| Campo | Tipo | Lungh. |
|---|---|---|
| QID | CHAR | 32 |
| STATUS | CHAR | 1 |
| MESSAGE | CHAR | 255 |
| PROCID | CHAR | 20 |

## Strutture `/EWF/S_ENG_KV` e `/EWF/S_DOC_ITEM` (invariate)

`/EWF/S_ENG_KV`: NAME CHAR30, VALUE CHAR255.
`/EWF/S_DOC_ITEM`: ITEM CHAR10, MENGE (rif. MEINS), MEINS, NETWR (rif. WAERS), WAERS.
