# ewf-s4cloud

Integrazione di eWF con **S/4HANA Cloud Public Edition** tramite API OData. Non usa JCo.
È la strada D del registro delle decisioni (D23): SAP trattiene il documento, eWF decide.

## Come funziona (OdA)

1. Il Flexible Workflow di SAP decide **se** un OdA va approvato, con i criteri dei funzionali. In quel caso l'OdA passa in stato **03**.
2. Il job `ewf.s4-po-poll` legge gli OdA modificati dopo l'ultimo punto di ripresa (`LastChangeDateTime`) e traduce lo stato in un evento per il motore:

   | Stato SAP | Evento | Cosa fa il motore |
   |---|---|---|
   | 03 in approvazione | `TOUCHED` (nuovo) | Senza processi aperti: avvio con le regole di avvio eWF. Con un processo aperto: confronto con la fotografia, e ripartenza se la modifica supera la tolleranza |
   | 05 rilasciato | `APPROVED_IN_SOURCE` | Chiude con OK il task INTERACTIVE aperto |
   | 08 respinto | `REJECTED_IN_SOURCE` | Chiude con KO il task INTERACTIVE aperto |
   | OdA cancellato, o senza posizioni attive | `TOUCHED` (non esiste) | Annulla i processi aperti |
   | Altri stati | Nessun evento | Il punto di ripresa avanza e basta |

3. L'ultimo task del processo è **INTERACTIVE**: da eWF non si approva. L'utente apre la My Inbox e decide lì.
4. Il parametro del task `EXPECT=OK` o `EXPECT=KO` indica quale decisione eWF si aspetta:
   - il task "Rilascia in SAP", dopo l'approvazione eWF, ha `EXPECT=OK`;
   - il task "Respingi in SAP", dopo un rifiuto in eWF, ha `EXPECT=KO`.
5. Il processo va in **errore** e compare in "Processi in errore" in due casi:
   - SAP decide prima che il processo arrivi al task INTERACTIVE;
   - SAP decide in modo diverso da quanto atteso.

## Configurazione (variabili d'ambiente, in `setenv.bat`)

`NAME` è il nome del sistema, cioè `backend_system.connection_ref`.

| Variabile | Esempio |
|---|---|
| `EWF_S4_NAME_URL` | `https://my434383-api.s4hana.cloud.sap` |
| `EWF_S4_NAME_USER` | `EWF_COMM` |
| `EWF_S4_NAME_PASSWD` | La password va **solo** qui: mai in file, nel WAR o in Git |

Sul tenant serve il communication arrangement **SAP_COM_0053** (`API_PURCHASEORDER_2`), con l'utente di comunicazione.

## Regola di avvio

Gli OdA di S/4 Public hanno lo stesso tipo documento `F` di quelli on-premise. Per questo il contesto contiene anche `backend_kind = S4_PUBLIC`.

La regola di avvio del processo S/4 va scritta così:
- condizione `backend_kind = S4_PUBLIC`;
- **priorità alta**, per esempio 100, così prevale sulle regole del processo on-premise, che terminano con il rilascio via BAPI.

## Parametri del job (`eone_connect.job.params`)

| Parametro | Significato |
|---|---|
| `batch` | OdA letti per giro (default 50) |
| `maxRounds` | Giri consecutivi per esecuzione (default 10) |
| `attempts` | Tentativi su un evento prima di superarlo (default 20) |
| `since` | Punto di partenza la prima volta, in ISO UTC. Se manca: l'ora del primo giro, quindi gli OdA precedenti non si ripescano |

Il punto di ripresa si trova in `eone_connect.sync_state`, con scope = sistema ed entity = `PURCHASE_ORDER`.

## Limiti noti

- Un OdA **rilasciato al salvataggio** perché il Flexible Workflow non ha trovato l'approvatore passa direttamente a 05, senza mai essere in 03. eWF non può distinguerlo da un OdA sotto soglia. La difesa è la configurazione SAP, con il test del team vuoto. Una "regola sentinella" in eWF, cioè il criterio copiato solo per l'allarme, è un'opzione che resta aperta.
- Il link alla My Inbox non è posizionato sull'attività: l'API dell'OdA non restituisce l'id del work item. Si veda il punto aperto sulla strada B (SAP_COM_0501) nella D23.
- Il conteggio dei tentativi è tenuto in memoria e si azzera al riavvio.
