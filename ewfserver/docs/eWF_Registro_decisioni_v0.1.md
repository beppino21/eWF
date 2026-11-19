# eWF – Registro delle decisioni

**Stato:** bozza 0.1 – novembre 2026.

Raccoglie le scelte di progetto prese durante lo sviluppo del prototipo, con il motivo e le alternative scartate. Serve a non ridiscutere ciò che è già deciso e a sapere **perché** il sistema si comporta così. Una decisione si può rivedere: in quel caso si aggiunge una voce nuova che sostituisce la vecchia, senza cancellarla.

**Documenti collegati:** Analisi funzionale (0.3); Architettura e base dati (0.2); Dal prototipo alla produzione.

# 1. Indice delle decisioni

| N. | Decisione | Data | Stato |
| --- | --- | --- | --- |
| D01 | L'annullamento di un processo non agisce su SAP | nov 2026 | Valida (per ora) |
| D02 | Pulsante Allegati sempre attivo per gli allegati del documento | nov 2026 | Valida |
| D03 | Allegati del documento: un'interfaccia, due adattatori (RFC e OData) | nov 2026 | Valida, da realizzare |
| D04 | Team e utenti si gestiscono in ewfserver, non nel designer | nov 2026 | Valida |
| D05 | Gantt disegnato come SVG lato server, con scala compressa | nov 2026 | Valida |
| D06 | Amministratori = membri del team di riserva; almeno uno sempre attivo | nov 2026 | Valida |
| D07 | Auto-sostituzione come nel ticketing: un solo sostituto alla volta | nov 2026 | Valida |
| D08 | Una sola appartenenza per team e utente | nov 2026 | Valida, da rivedere se serve lo storico |
| D09 | Rilascio con hot deploy del designer, nessuna copia manuale | nov 2026 | Valida |
| D10 | Anteprima del designer con origine ammessa, filtro sempre attivo | nov 2026 | Valida |
| D11 | Icona per tipo processo nel menu rinviata | nov 2026 | Rinviata |
| D12 | Password legacy non migrate; segreti solo in variabili d'ambiente | ott 2026 | Valida |

# 2. Le decisioni

## D01 – L'annullamento non agisce su SAP

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Quando un amministratore annulla un processo, eWF chiude processo e task e registra il motivo nell'audit. Non chiama nulla in SAP: il documento resta com'è. |
| **Perché** | Cancellare o rilasciare un documento è una decisione di business, non di amministrazione del workflow. Il legacy aveva un caso (flag di cancellazione `LOEKZ = L` sulle posizioni dell'OdA via BAPI, al rifiuto dell'attività di avvio) che era una personalizzazione per un cliente, non un comportamento generale. |
| **Scartato** | Riprodurre la BAPI del cliente come comportamento standard. |
| **Conseguenze** | Il documento annullato va gestito in SAP a mano. Se servirà, l'azione all'annullamento si configurerà per tipo processo (`ON_CANCEL`), come l'azione di fine processo, usando eventualmente una RFC già esistente. |

## D02 – Pulsante Allegati sempre attivo per gli allegati del documento

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Per i tipi processo con allegati nel sistema di origine (DOCUMENT) il pulsante Allegati in riga è sempre attivo e senza numero. Per gli allegati in eWF (PROCESS) è attivo ed evidenziato solo se ce ne sono. |
| **Perché** | Contare gli allegati SAP vorrebbe dire una chiamata a SAP per ogni riga della lista, a ogni aggiornamento: lento, e la lista diventerebbe dipendente dalla disponibilità di SAP. |
| **Scartato** | Contarli al caricamento della lista; tenere una copia del numero in eWF (diventerebbe vecchio). |
| **Conseguenze** | L'utente può aprire la finestra e trovarla vuota. Il numero si vede nel dettaglio, dove la chiamata è una sola. |

## D03 – Allegati del documento: un'interfaccia, due adattatori

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Gli allegati del documento passano dall'interfaccia `DocumentAttachments`. Si scriveranno due realizzazioni: RFC (GOS) per R/3 e S/4 on-premise, OData (`API_CV_ATTACHMENT_SRV`) per S/4 Public, nello stesso piano dell'adattatore OData del documento. |
| **Perché** | Stessa logica già usata per schede e collegamento SAP GUI: il motore e le pagine non sanno quale SAP c'è dietro. |
| **Scartato** | Copiare gli allegati SAP nel database eWF. |
| **Conseguenze** | Finché gli adattatori non ci sono, la modalità DOCUMENT non mostra allegati. |

## D04 – Team e utenti in ewfserver

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Utenti, team e appartenenze si gestiscono dalle funzioni da amministratore dell'applicazione web. Il futuro designer dei processi li usa (sceglie un team da un elenco) ma non li crea. |
| **Perché** | I team sono organizzazione e cambiano con le persone; le definizioni dei processi cambiano con le regole. Hanno ritmi e responsabili diversi. |
| **Scartato** | Gestirli nel designer. |
| **Conseguenze** | Il designer avrà bisogno solo di lettura su `ewf_org`. |

## D05 – Gantt come SVG lato server

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Il Gantt del processo è un'immagine SVG generata in Java (`GanttSvg`) e mostrata con `t:svgviewer`. Una riga per persona, scala del tempo compressa (logaritmica sugli intervalli), attese lunghe segnate `//`, dettagli nel suggerimento al passaggio del mouse. |
| **Perché** | Il componente di pianificazione di CaptainCasa, provato per primo, metteva le attività su una riga sola e con scala lineare: un'approvazione di un minuto in un processo di settimane non si vedeva. L'SVG dà controllo completo, nessuna libreria in più, ed è verificabile fuori dal browser. |
| **Scartato** | Componente schedule di CaptainCasa; libreria grafica esterna. |
| **Conseguenze** | Le lunghezze non sono proporzionali: le durate si leggono nei testi. La stessa tecnica si userà per il diagramma del flusso nel designer. |

## D06 – Chi è amministratore

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Sono amministratori i membri attivi del team di riserva del motore (default `WFADM`). Deve restarne sempre almeno uno attivo: il controllo è nella stessa transazione della modifica. Un amministratore non può togliersi né disattivarsi da solo. |
| **Perché** | Chi riceve i task senza titolare è già, di fatto, chi governa il workflow. Un ruolo separato sarebbe un'altra cosa da configurare e da tenere allineata. |
| **Scartato** | Flag "amministratore" sull'utente; ruolo separato. |
| **Conseguenze** | Aggiungere qualcuno a `WFADM` gli dà anche i task senza titolare. |

## D07 – Auto-sostituzione come nel ticketing

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | L'utente abilitato indica un solo sostituto alla volta, con un periodo; uno nuovo sostituisce il precedente. Il sostituto riceve una mail. La pagina "Sostituzioni" è di sola consultazione. L'amministratore gestisce le sostituzioni di tutti. |
| **Perché** | Uniformità con l'applicazione di ticketing, già in uso dagli stessi utenti; un sostituto alla volta evita ambiguità su chi riceve mail e task. |
| **Scartato** | Più sostituti contemporanei per lo stesso utente. |
| **Conseguenze** | Sostituzioni multiple restano possibili solo da amministratore, senza sovrapposizioni. |

## D08 – Una sola appartenenza per team e utente

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Si mantiene la chiave (team, utente) di `team_member`. Riaggiungere chi era uscito sovrascrive il periodo precedente. |
| **Perché** | È la struttura esistente e basta per l'uso attuale; cambiarla richiede una migrazione. |
| **Scartato** | Chiave con la data di inizio per conservare tutti i periodi. |
| **Conseguenze** | Lo storico delle appartenenze passate non è completo. Da rivedere se servirà per audit. |

## D09 – Rilascio con hot deploy del designer

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Pagine XML e classi del pacchetto `view` si rilasciano con l'hot deploy del designer; il resto con Maven install e Reload. Niente copie manuali né script di copia in `WEB-INF/classes`. |
| **Perché** | Le copie manuali fatte in sviluppo lasciavano versioni vecchie che prevalevano su quelle nuove, e la pagina "non cambiava". Lo script di copia era una toppa sul sintomo. |
| **Scartato** | Script di copia (BAT). |
| **Conseguenze** | Regola da scrivere nella guida di esercizio. |

## D10 – Anteprima del designer e intestazioni di sicurezza

| Voce | Contenuto |
| --- | --- |
| **Data** | 18/11/2026 |
| **Decisione** | Il filtro delle intestazioni di sicurezza resta sempre attivo. In sviluppo `EWF_FRAME_ALLOWED` ammette l'incorporamento solo dall'origine del designer (`http://localhost:51000`). |
| **Perché** | Spegnere il filtro in sviluppo rende diverso l'ambiente di prova da quello reale e rischia di arrivare in produzione spento. |
| **Scartato** | Spegnere il filtro (`EWF_SECURITY_HEADERS=false`), che resta disponibile ma sconsigliato. |
| **Conseguenze** | In produzione la variabile non va impostata. |

## D11 – Icona per tipo processo rinviata

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Le schede del menu e le righe non hanno un'icona per tipo processo. |
| **Perché** | Non è essenziale ora; se ne riparla quando ci saranno più tipi processo. |
| **Scartato** | – |
| **Conseguenze** | Nessuna. |

## D12 – Password e segreti

| Voce | Contenuto |
| --- | --- |
| **Data** | ott 2026 |
| **Decisione** | Le password del legacy non si migrano: l'amministratore genera una password provvisoria. Password di database, SAP e posta solo in variabili d'ambiente (`setenv.bat`), mai in file, war o Git. |
| **Perché** | Il legacy conservava le password in modo non adatto; i segreti nei file finiscono prima o poi in un repository. |
| **Scartato** | Migrare le password; tenerle in `config.properties`. |
| **Conseguenze** | Al primo avvio ogni utente riceve una password provvisoria. |

