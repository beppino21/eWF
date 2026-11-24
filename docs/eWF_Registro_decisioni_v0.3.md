# eWF – Registro delle decisioni

**Stato:** bozza 0.3 – 23 novembre 2026 (0.2: decisioni D16–D20, designer e regole; 0.3: D21–D22, versione mobile e commento sulle azioni).

Raccoglie le scelte di progetto prese durante lo sviluppo del prototipo, con il motivo e le alternative scartate. Serve a non ridiscutere ciò che è già deciso e a sapere **perché** il sistema si comporta così. Una decisione si può rivedere: in quel caso si aggiunge una voce nuova che sostituisce la vecchia, senza cancellarla.

**Documenti collegati:** Analisi funzionale (0.4); Architettura e base dati (0.3); Dal prototipo alla produzione.

# 1. Indice delle decisioni

| N. | Decisione | Data | Stato |
| --- | --- | --- | --- |
| D01 | L'annullamento di un processo non agisce su SAP | nov 2026 | Valida (per ora) |
| D02 | Pulsante Allegati sempre attivo per gli allegati del documento | nov 2026 | Sostituita da D13 |
| D03 | Allegati del documento: un'interfaccia, due adattatori (RFC e OData) | nov 2026 | Valida; RFC realizzato |
| D04 | Team e utenti si gestiscono in ewfserver, non nel designer | nov 2026 | Valida |
| D05 | Gantt disegnato come SVG lato server, con scala compressa | nov 2026 | Valida |
| D06 | Amministratori = membri del team di riserva; almeno uno sempre attivo | nov 2026 | Valida |
| D07 | Auto-sostituzione come nel ticketing: un solo sostituto alla volta | nov 2026 | Valida |
| D08 | Una sola appartenenza per team e utente | nov 2026 | Valida, da rivedere se serve lo storico |
| D09 | Rilascio con hot deploy del designer, nessuna copia manuale | nov 2026 | Valida, motivo in D14 |
| D10 | Anteprima del designer con origine ammessa, filtro sempre attivo | nov 2026 | Valida |
| D11 | Icona per tipo processo nel menu rinviata | nov 2026 | Rinviata |
| D12 | Password legacy non migrate; segreti solo in variabili d'ambiente | ott 2026 | Valida |
| D13 | Numero degli allegati SAP con una sola RFC per pagina | nov 2026 | Valida |
| D14 | Hot deploy solo per il pacchetto view | nov 2026 | Valida |
| D15 | Allegati: modalità BOTH (SAP + eWF) consigliata | nov 2026 | Valida |
| D16 | Eccezioni, gruppi e autorizzazioni del legacy fuori dallo standard | nov 2026 | Valida |
| D17 | Versioni non in bozza protette anche nel database | nov 2026 | Valida |
| D18 | Designer dentro ewfserver, con moduli e diagramma generato | nov 2026 | Valida |
| D19 | Nuova versione copiata nel database, non attraverso il modello | nov 2026 | Valida |
| D20 | Simulatori con lo stesso codice del motore | nov 2026 | Valida |
| D21 | Versione mobile: pagine dedicate, stessi servizi | nov 2026 | Valida |
| D22 | Commento sulle azioni impostabile per task, controllato dal motore | nov 2026 | Valida |

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
| **Conseguenze** | L'utente può aprire la finestra e trovarla vuota. Il numero si vede nel dettaglio, dove la chiamata è una sola. **Sostituita da D13:** il conteggio si può fare con una sola chiamata per tutta la pagina. |

## D03 – Allegati del documento: un'interfaccia, due adattatori

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Gli allegati del documento passano dall'interfaccia `DocumentAttachments`. Si scriveranno due realizzazioni: RFC (GOS) per R/3 e S/4 on-premise, OData (`API_CV_ATTACHMENT_SRV`) per S/4 Public, nello stesso piano dell'adattatore OData del documento. |
| **Perché** | Stessa logica già usata per schede e collegamento SAP GUI: il motore e le pagine non sanno quale SAP c'è dietro. |
| **Scartato** | Copiare gli allegati SAP nel database eWF. |
| **Conseguenze** | Realizzato via RFC (`/EWF/DOC_ATTACH_LIST`, `_GET`, `_COUNT`) per GOS e ArchiveLink, in sola consultazione. Restano l'adattatore OData e il caricamento in SAP. |

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
| **Conseguenze** | Regola da scrivere nella guida di esercizio. Il motivo di fondo, emerso dopo, è in D14. |

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

## D13 – Numero degli allegati SAP con una sola RFC per pagina

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Il pulsante Allegati della lista mostra anche il numero degli allegati SAP. eWF chiede i numeri di tutti i documenti della pagina con una sola chiamata (`/EWF/DOC_ATTACH_COUNT`) e li somma a quelli in eWF. Se SAP non risponde, o la FM manca, il pulsante resta attivo senza numero. |
| **Perché** | Il problema di D02 era una chiamata per riga; con una chiamata per pagina il costo è trascurabile. Un pulsante sempre attivo che apre finestre vuote confondeva gli utenti. |
| **Scartato** | Pulsante sempre attivo (D02); copia del numero nel database eWF. |
| **Conseguenze** | La lista dipende da SAP solo per il numero, mai per funzionare. |

## D14 – Hot deploy solo per il pacchetto view

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Nel `.ccproject` di ewfserver i pacchetti hot deploy sono solo `it.eone.ewf.ewfserver.view`, non `*` come propone il designer. |
| **Perché** | Tomcat carica con il classloader dell'applicazione le classi dichiarate nel `web.xml` (listener `JobHost`, filtro `SecurityHeadersFilter`), e quel classloader non vede l'hot deploy. Con `*` la `WEB-INF/classes` resta vuota e l'applicazione non parte. Il problema era nascosto da copie manuali residue: è emerso quando il designer le ha tolte. |
| **Scartato** | Lasciare `*` e spostare listener, filtro e avvio dei servizi in un jar a parte: corretto, ma un modulo in più. |
| **Conseguenze** | Le classi `app`, `config`, `context` e `web` cambiano solo con Maven install + Reload; le pagine continuano con l'hot deploy. Le classi fuori da `view` non devono mai usare classi di `view`. |

## D15 – Allegati: modalità BOTH consigliata

| Voce | Contenuto |
| --- | --- |
| **Data** | 19/11/2026 |
| **Decisione** | Nuovo valore `BOTH` di `attachment_scope` (schema 0.15): allegati del documento SAP e allegati eWF in un elenco unico con la colonna Origine; i nuovi file vanno in eWF. È la modalità consigliata; `EONE_ODA` la usa. |
| **Perché** | Con gli allegati SAP in sola consultazione, DOCUMENT da solo toglieva la possibilità di allegare e PROCESS da solo nascondeva le offerte in GOS. Quasi sempre servono entrambi. |
| **Scartato** | Scegliere tra PROCESS e DOCUMENT per tipo processo. |
| **Conseguenze** | Gli allegati eWF caricati prima del passaggio restano visibili. Quando ci sarà il caricamento in SAP, si deciderà dove mandare i nuovi file. |

## D16 – Eccezioni, gruppi e autorizzazioni del legacy fuori dallo standard

| Voce | Contenuto |
| --- | --- |
| **Data** | 20/11/2026 |
| **Decisione** | Le eccezioni di assegnazione (`WF_CLI_USER`), i gruppi di utenti intercambiabili (`CDO_USERGRP`) e le autorizzazioni per processo (`CDO_AUTH`, `USR_AUTH`) non fanno parte dello standard eWF. Si realizzeranno su richiesta del cliente, separate dallo standard. |
| **Perché** | Erano funzioni nate per singoli clienti. Portarle nello standard renderebbe le regole di assegnazione più difficili da capire per tutti. |
| **Scartato** | Farle funzionare subito nel motore e nelle pagine standard. |
| **Conseguenze** | Le tabelle restano e la migrazione le riempie, ma il motore non le usa. La pagina delle assegnazioni gestisce solo ruoli e regole. |

## D17 – Versioni non in bozza protette anche nel database

| Voce | Contenuto |
| --- | --- |
| **Data** | 20/11/2026 |
| **Decisione** | Dei trigger (schema 0.16) impediscono all'utente applicativo `ewf_app` di modificare versioni, step, task, agganci, opzioni e funzioni di una versione che non è in bozza; una versione attiva può solo essere ritirata. |
| **Perché** | Su una versione attiva girano dei processi: cambiarla per errore altererebbe processi in corso. Il controllo c'è già nel servizio; ripeterlo nel database lo rende indipendente da eventuali errori del codice. |
| **Scartato** | Solo il controllo nel servizio. |
| **Conseguenze** | Valgono solo per `ewf_app`: script e migrazioni eseguiti da `ewf_owner` non sono toccati. Alcuni client SQL spezzano i blocchi `$$` delle funzioni: questi script vanno eseguiti con `psql`. |

## D18 – Designer dentro ewfserver, con moduli e diagramma generato

| Voce | Contenuto |
| --- | --- |
| **Data** | 20/11/2026 |
| **Decisione** | Il designer dei processi è una funzione da amministratore di ewfserver. Si modifica con moduli (step, task, opzioni…); il diagramma è generato lato server come SVG e si aggiorna a ogni modifica. |
| **Perché** | Accesso, utenti, team e ruoli sono già in ewfserver. Un editor grafico a trascinamento richiederebbe un componente fatto apposta, con un costo sproporzionato per processi di 4–10 step. |
| **Scartato** | Applicazione separata `ewf-designer`; modifica trascinando i riquadri sul disegno. |
| **Conseguenze** | Un designer esterno o un secondo strumento di modifica restano possibili in seguito, sulle stesse tabelle e con le stesse regole. |

## D19 – Nuova versione copiata nel database, non attraverso il modello

| Voce | Contenuto |
| --- | --- |
| **Data** | 20/11/2026 |
| **Decisione** | "Nuova versione da questa" copia le righe di step, task, agganci, opzioni e funzioni direttamente nel database, con tutte le colonne (lette dal catalogo), invece di caricare la versione nel modello del motore e risalvarla. |
| **Perché** | Il modello del motore non conosce tutti i dati della definizione (per esempio le funzioni speciali e alcune colonne ereditate dal legacy): passando dal modello si perderebbero. |
| **Scartato** | Copia attraverso `ProcessVersion` e `saveVersion`. |
| **Conseguenze** | Una colonna aggiunta in futuro alle definizioni viene copiata senza modificare il codice. |

## D20 – Simulatori con lo stesso codice del motore

| Voce | Contenuto |
| --- | --- |
| **Data** | 20/11/2026 |
| **Decisione** | I simulatori "Chi lo riceve?" e "Quale processo parte?" usano le stesse funzioni con cui il motore assegna i task (`RuleBasedAssignmentResolver.explain`) e sceglie il processo da avviare (`StartRule.choose`). |
| **Perché** | Un simulatore con una logica sua potrebbe dare risposte diverse dal motore: sarebbe peggio che non averlo. |
| **Scartato** | Riscrivere la logica nella pagina o nel servizio. |
| **Conseguenze** | L'unica differenza è voluta: se manca un cambio valuta, il motore dà errore, il simulatore mostra la regola come "importo non confrontabile". |

## D21 – Versione mobile: pagine dedicate, stessi servizi

| Voce | Contenuto |
| --- | --- |
| **Data** | 23/11/2026 |
| **Decisione** | Per il telefono ci sono pagine apposite (`MobileTasks`, `MobileTask`): lista a card, dettaglio essenziale, azione con conferma. Si aprono da sole se il browser è di un telefono (`?mobile=1` / `?mobile=0` per forzare). Usano solo `EwfService`, come le pagine desktop. |
| **Perché** | Le pagine desktop (griglie larghe, schede, Gantt) su un telefono non diventano comode adattandole; pagine piccole e dedicate sì, e costano poco perché la logica è tutta nei servizi. |
| **Scartato** | Rendere "responsive" le pagine esistenti. |
| **Conseguenze** | Fuori dalla rete interna la versione mobile si usa solo dopo le decisioni del capitolo 10 di "Dal prototipo alla produzione" (VPN o pubblicazione con HTTPS e MFA). In CaptainCasa il colore del testo va in `foreground` e i testi lunghi in `textpane` (le `label` non vanno a capo). |

## D22 – Commento sulle azioni impostabile per task, controllato dal motore

| Voce | Contenuto |
| --- | --- |
| **Data** | 23/11/2026 |
| **Decisione** | Ogni task ha due impostazioni nel designer: commento all'approvazione e commento al rifiuto (non richiesto / facoltativo / obbligatorio). Predefiniti: approvazione facoltativo, rifiuto obbligatorio, applicati anche alle versioni già attive. Le opzioni dei nodi di scelta seguono la regola del rifiuto se respingono, altrimenti quella dell'approvazione. Il motore rifiuta l'azione se il commento obbligatorio manca; la web app chiede il commento con un riquadro di conferma solo quando serve, il mobile lo chiede sempre nella schermata di azione. |
| **Perché** | Chi respinge deve dire perché, ma un obbligo uguale per tutti i task sarebbe troppo rigido. Il controllo nel motore vale per ogni canale (web, mobile, API futura): nessuna pagina può dimenticarselo. |
| **Scartato** | Regola fissa per tutti; controllo solo nelle pagine; conferma sempre anche nella web app (un clic in più per chi approva molto). |
| **Conseguenze** | Schema 0.17 (`task_def.comment_on_approve`, `comment_on_reject`). Le azioni automatiche del sistema (es. approvazione all'avvio) non sono soggette alla regola. |

