# Coda degli avvii: oggetti DDIC (pacchetto /EWF/DDIC)

## Tabella `/EWF/START_Q` — "eWF: coda avvii processi"
Classe di consegna **A**, dipendente dal mandante, categoria di dimensione 2 (crescita e pulizia periodica).

| Campo | Chiave | Tipo | Lungh. | Descrizione |
|---|---|---|---|---|
| MANDT | X | MANDT | | Mandante |
| QID | X | CHAR | 32 | Id riga (UUID) |
| TIPODOC | | /EWF/WF_TIPODOC | | Tipo documento (F, B, FA, …) |
| DOCUMENT | | /EWF/WF_DOCUMENT | | Chiave documento |
| EVENT | | CHAR | 10 | CREATED / RESET / CHANGED / DELETED |
| UNAME | | SYUNAME | | Utente SAP che ha salvato |
| CREATED_AT | | TIMESTAMPL | | Quando è nato l'evento |
| STATUS | | CHAR | 1 | ' ' da elaborare, D fatto, W fatto con avviso, E errore (si ritenta) |
| ATTEMPTS | | INT2 | | Tentativi di elaborazione |
| MESSAGE | | CHAR | 255 | Esito da eWF |
| PROCID | | CHAR | 20 | Processo eWF avviato/coinvolto |
| PROCESSED_AT | | TIMESTAMPL | | Ultima elaborazione |

Indice secondario `S01`: MANDT, STATUS, CREATED_AT.

## Struttura `/EWF/S_START_RES` — esito di elaborazione (per `/EWF/START_Q_CONFIRM`)

| Campo | Tipo | Lungh. |
|---|---|---|
| QID | CHAR | 32 |
| STATUS | CHAR | 1 |
| MESSAGE | CHAR | 255 |
| PROCID | CHAR | 20 |

## Function module (stesso gruppo funzioni di `/EWF/ENGINE_DISPATCH`)

| FM | Tipo | Chi la chiama |
|---|---|---|
| `/EWF/START_Q_WRITE` | **modulo di aggiornamento** (avvio immediato) | le exit di salvataggio, `IN UPDATE TASK` |
| `/EWF/START_Q_READ` | **RFC** | eWF (lettura periodica) |
| `/EWF/START_Q_CONFIRM` | **RFC** | eWF (esiti) |

## Struttura `/EWF/S_DOC_ITEM` — posizione per `/EWF/DOC_INFO` (tabella `ET_ITEMS`)

| Campo | Tipo | Riferimento |
|---|---|---|
| ITEM | CHAR 10 | numero posizione (EBELP / BNFPO) |
| MENGE | MENGE_D (QUAN 13,3) | unità: MEINS |
| MEINS | MEINS | |
| NETWR | NETWR (CURR 15,2) | divisa: WAERS |
| WAERS | WAERS | |

Riferimenti obbligatori in SE11: MENGE → `/EWF/S_DOC_ITEM-MEINS`, NETWR → `/EWF/S_DOC_ITEM-WAERS`.
