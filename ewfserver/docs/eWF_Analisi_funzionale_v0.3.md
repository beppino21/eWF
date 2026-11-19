# eWF – Analisi funzionale del prototipo

Workflow approvativo e-One: motore Java su PostgreSQL, collegato a SAP S/4HANA, con applicazione web CaptainCasa.

**Destinatari:** colleghi del gruppo di sviluppo e-One, esperti di SAP.

**Stato:** bozza 0.3 – novembre 2026. 0.2: avvio automatico allineato al codice. 0.3: menu e pagina iniziale, sostituzioni e auto-sostituzione, processi a cui ho partecipato, Gantt, funzioni da amministratore (capitoli 5 e 6). Descrive il comportamento del prototipo verificato sul sistema DEV (S/4HANA Private, ordini d'acquisto).

**Documenti collegati:** Architettura e base dati; Dal prototipo alla produzione; Registro delle decisioni. Da scrivere: Guida al riuso; Esercizio e configurazione.

# 1. Scopo del documento

Questo documento descrive **cosa fa** eWF, non come è costruito. Spiega i concetti (processo, step, task, ruoli, sostituti, esiti…), le regole che il sistema applica e lo scenario dell'ordine d'acquisto così come funziona oggi nel prototipo.

L'architettura tecnica e la base dati sono descritte in un documento a parte. Qui i nomi tecnici compaiono solo dove servono a ritrovare un concetto nel sistema: per esempio il nome di una tabella o di un campo di configurazione.

Per chi conosce il vecchio eWF ABAP (namespace `/EWF/`) è indicata, dove utile, la corrispondenza con gli oggetti legacy. Il glossario finale le riassume.

# 2. eWF in breve

eWF è un sistema di **approvazione dei documenti**. Quando in SAP nasce o cambia un documento che richiede approvazione, per esempio un ordine d'acquisto, eWF avvia un **processo**: una sequenza di passi, ognuno affidato a una o più persone, che si conclude con un esito positivo (il documento viene rilasciato) o negativo.

## 2.1 Cosa cambia rispetto al legacy /EWF/

Nel vecchio eWF tutto stava in SAP: definizioni, processi in corso, utenti e logica, in tabelle e function module del namespace `/EWF/`. Nel nuovo eWF i compiti sono divisi così:

| Dove | Cosa contiene |
| --- | --- |
| **SAP** | Resta il sistema dei documenti: ordini, fornitori, strategia di rilascio. eWF lo legge e, quando serve, gli chiede di eseguire azioni (controllo blocchi, rilascio dell'OdA) attraverso function module RFC. |
| **Motore eWF** | Programma Java che decide l'avanzamento dei processi. Non contiene logica SAP: quando deve "fare qualcosa" in SAP chiama un **handler**. |
| **Database eWF** | PostgreSQL: definizioni dei processi, organizzazione (utenti, ruoli, team, sostituti), processi in corso, storico e audit. |
| **Applicazione web** | CaptainCasa: lista delle attività, dettaglio, approvazione, commenti, allegati, schede PDF. Funziona da browser, senza SAP GUI. |

Il vantaggio principale è che il motore non dipende da una specifica versione di SAP. Lo stesso eWF può lavorare con R/3, S/4HANA on-premise o Private e, cambiando il collegamento, con la Public Edition.

# 3. I concetti

## 3.1 Tipo documento e documento

Il **tipo documento** dice di che oggetto SAP si tratta: per esempio `F` per l'ordine d'acquisto, come nel legacy (`Z_WF_TIPODOC`). Il **documento** è la singola istanza, identificata dalla sua chiave (per l'OdA il numero, EBELN).

Per ogni tipo documento esiste un **adattatore** che sa leggerlo in SAP. Per l'OdA è la FM `/EWF/DOC_INFO`, che dice tre cose: se il documento esiste, quanto vale (nella divisa standard del mandante) e quali sono i suoi attributi (divisione, società, gruppo acquisti, fornitore, tipo OdA…).

## 3.2 Tipo processo e versione

Il **tipo processo** è il modello di approvazione: per esempio `EONE_ODA`, "Autorizzazione ordini d'acquisto". È legato a un tipo documento.

La definizione è **versionata**. Una versione nasce in bozza (DRAFT), viene validata e attivata (ACTIVE) e, quando ne arriva una nuova, viene ritirata (RETIRED). Per ogni tipo processo c'è una sola versione attiva.

> **Regola importante:** un processo resta sempre sulla versione con cui è partito. Se si cambia la definizione, i processi già in corso proseguono con le regole vecchie; solo i nuovi usano quelle nuove.

Sul tipo processo si configurano anche il riepilogo che compare nella lista delle attività, le colonne specifiche, il semaforo dei giorni di attesa e dove stanno gli allegati (vedi capitolo 5).

## 3.3 Step e task

Una versione è fatta di **step** numerati (10, 20, 30… come `Z_WF_STEP`). Ogni step contiene uno o più **task** (come `Z_WF_SUBOP`): un task è un'attività affidata a qualcuno.

- **Più task nello stesso step** sono attività **parallele**. La regola di ricongiungimento dello step dice quando lo step è concluso: `ALL` = tutti devono approvare; `ANY` = basta il primo.
- Ogni step indica dove andare **se approvato** (step successivo OK) e **se respinto** (step successivo KO). Vuoto significa fine del processo, come il 99999 del legacy.
- Uno step può avere una **soglia d'importo** (come `Z_WF_LIMIT`): se il valore del documento è sotto la soglia, lo step viene saltato.
- Lo step marcato come **primo** è quello da cui parte il processo.

## 3.4 Chi riceve il task: ruoli e assegnazione

Ogni task ha una **modalità di assegnazione**, che stabilisce chi ne diventa titolare:

| Modalità | Significato | Legacy |
| --- | --- | --- |
| `RULES` | Si cerca l'assegnatario nelle regole di assegnazione, in base al ruolo del task (vedi sotto). | `/EWF/WF_USER_TASK` |
| `STARTER` | Chi ha avviato il processo. | `SY_START`, `WF_USER_TASK_MIGO` |
| `ACTOR` | Chi ha eseguito il task precedente. | `SY_UNAME` |
| `PREVIOUS` | L'assegnatario del task precedente. | `>>>SKIP_TASK` |
| `HANDLER` | Lo decide una funzione specifica (handler di assegnazione). | FM specifiche in `THIS_USER` |

### Le regole di assegnazione

Le regole (tabella `assignment_rule`, l'equivalente di `USER_TASK`) dicono: per questo **ruolo**, eventualmente limitato a un tipo processo, un gruppo acquisti, una divisione, un magazzino, una fascia d'importo e un periodo di validità, il destinatario è **questo utente** oppure **questo team**.

Il legacy faceva cinque SELECT a specificità decrescente. Il nuovo motore generalizza così: tra tutte le regole compatibili con il documento **vince quella con più criteri valorizzati**; a parità, quella creata per prima. L'importo viene convertito nella divisa della regola alla data del giorno.

> **Nessun task resta senza titolare.** Se l'assegnatario non si trova, non è mappato o non è attivo, il task va al **team di riserva** (default `WFADM`) e l'evento viene registrato nell'audit. Il team di riserva deve esistere e avere almeno un membro.

### I team

Team e membri si gestiscono dall'applicazione (amministratori, capitolo 6): ogni appartenenza ha un periodo di validità (dal… al…, anche senza limiti). Un utente ha una sola appartenenza per team.

Un task può essere affidato a un **team** invece che a una persona. Tutti i membri del team lo vedono nella propria lista e chiunque di loro può agire. Il motore prevede anche la **presa in carico**: un membro prende il task, che sparisce dalle liste degli altri finché non lo rilascia.

## 3.5 Il peso (livello di autorizzazione)

Ogni task ha un **peso richiesto** (`Z_WF_LIV`) e ogni utente ha un **peso** (`PUNTEGGIO`), eventualmente ridefinito per un singolo tipo processo. Per approvare o respingere serve un peso effettivo **maggiore o uguale** a quello del task. È il controllo che impedisce, per esempio, che un sostituto di livello inferiore firmi un rilascio di alto importo.

## 3.6 I sostituti

Un utente può avere un **sostituto** per un periodo (dal… al…), come nella tabella `SOSTITUTI` del legacy. Nel periodo di validità il sostituto:

- vede nella propria lista i task del titolare, con l'indicazione "per conto di…", compresi quelli già in attesa;
- può agire su quei task, se il suo peso lo consente; nello storico resta scritto che ha agito come sostituto;
- riceve al posto del titolare le mail di nuovo task e il riepilogo periodico.

Chi decide le sostituzioni:

- **l'utente stesso** ("auto-sostituzione", come nell'applicazione di ticketing), se è abilitato: il permesso si indica sull'utente oppure, se sull'utente è vuoto, vale quello della sua classe utente. Un utente ha **un solo sostituto alla volta**: indicarne uno nuovo sostituisce il precedente. Il sostituto riceve una mail con il periodo e l'elenco delle attività già in attesa;
- **l'amministratore**, per chiunque ("sostituzione generalizzata"), per esempio per un'assenza imprevista o per chi non è abilitato.

Le sostituzioni non si sovrappongono e non partono nel passato. Una sostituzione in corso, quando viene tolta, si chiude a ieri e resta nello storico; una futura si cancella. Di ogni sostituzione resta chi l'ha decisa e quando.

## 3.7 Gli esiti di un task

| Azione | Effetto |
| --- | --- |
| **Approva** (OK) | Il task si chiude positivamente; quando lo step è concluso (secondo ALL/ANY) il processo va allo step successivo OK. |
| **Respingi** (KO) | Il task si chiude negativamente; il processo va allo step successivo KO, per esempio torna al richiedente, oppure finisce come respinto. La **nota è obbligatoria**. |
| **Sposta** | Il task passa a un altro utente, con nota facoltativa. Resta traccia di chi l'ha spostato e a chi. |
| **Scelta** | Solo nei **nodi di scelta** (vedi sotto): l'utente sceglie un'opzione al posto di Approva/Respingi. |

### Nodi di scelta e rami

Un task può avere un elenco di **opzioni**: in quel caso è un nodo di scelta e l'utente decide come proseguire, per esempio "Reso al fornitore" o "Continua". È la generalizzazione di `WF_SELEZIONE_OPZIONE` e dei processi "fittizi" del legacy.

- Ogni opzione prosegue come un'approvazione oppure, se marcata come rifiuto, come un rifiuto.
- Lo stesso numero di step può esistere nel **tronco comune** e in **varianti** legate a un'opzione: arrivato a quello step, il motore usa la variante dell'opzione scelta. I rami si ricongiungono puntando a uno step del tronco.
- Un handler eseguito all'attivazione del nodo può **decidere da solo** l'opzione (instradamento automatico). Se il codice restituito non è previsto, viene ignorato e il nodo aspetta l'utente.
- Un'opzione può **avviare un altro processo** sullo stesso documento: in passaggio di consegne (il processo corrente si chiude come "inoltrato") oppure in parallelo.

All'attivazione di una versione, il motore percorre tutte le combinazioni di scelte e **rifiuta** la versione se un ramo porta a uno step inesistente o se due varianti si sovrappongono.

## 3.8 Gli agganci (handler)

Quando il processo deve fare qualcosa fuori dal motore, per esempio verificare in SAP che l'OdA non sia in modifica o rilasciarlo, chiama un **handler**. Per SAP gli handler sono le function module `/EWF/` già esistenti, chiamate via RFC attraverso un unico punto d'ingresso, `/EWF/ENGINE_DISPATCH`.

Gli handler si agganciano agli eventi del task:

| Evento | Legacy | Quando |
| --- | --- | --- |
| `BEFORE_ACTION` | `FM_EXANTE` | Prima di OK/KO. Se l'handler risponde KO, l'azione **non viene eseguita** e il task resta aperto (es. `/EWF/WF_CHECK_LOCK_EKKO`: OdA aperto in ME22N). |
| `ON_OK` | `FM_OK` | Su approvazione, prima dell'avanzamento. |
| `ON_KO` | `FM_KO` | Su rifiuto. |
| `ON_ACTIVATE` | `FM_EXPOST` | Alla creazione del task. Se fallisce, il processo non si blocca: resta traccia nell'audit. |
| `BUTTON` | `FM_BUTTON01` | Azione aggiuntiva a richiesta dell'utente (vedi "funzioni speciali"). |

Oltre agli agganci dei task, ogni versione ha un'**azione di fine processo** (`Z_WF_FM_END_PROC`), che per l'OdA è il rilascio, `/EWF/WF_RIL_ODA` con il codice di rilascio configurato.

> **Regole fisse del motore:** lo stato dei processi lo cambia solo il motore; gli handler restituiscono un esito e al massimo alcune istruzioni (salta step, salta task, imposta un valore, scegli un'opzione). Gli handler non vengono mai chiamati dentro una transazione del database: il task passa allo stato "in esecuzione", si chiamano gli handler, poi si applica l'esito.

## 3.9 La fotografia del documento

All'avvio il motore legge il documento in SAP **una volta sola** e ne salva sul processo il valore, gli attributi (il "contesto") e il dettaglio delle posizioni (quantità e valore). Soglie e regole di assegnazione usano questa fotografia, senza richiamare SAP a ogni passo.

Per l'OdA il contesto contiene anche le **descrizioni** (fornitore, divisione, gruppo acquisti, tipo OdA, condizioni di pagamento), estratte dalla FM `/EWF/CTX_PO`. Servono alla lista delle attività e alle schede PDF.

## 3.10 Le funzioni speciali

A un task si possono associare **funzioni speciali**: pulsanti aggiuntivi nel dettaglio, ciascuno eseguito da un handler, con un eventuale testo di conferma prima dell'esecuzione. Sono l'equivalente evoluto dei pulsanti `FM_BUTTON` del legacy.

# 4. Il ciclo di vita

## 4.1 Stati del processo

| Stato | Significato |
| --- | --- |
| `RUNNING` | In corso. |
| `SUSPENDED` | Sospeso. |
| `COMPLETING` | Approvato: è in corso l'azione di fine processo (rilascio). |
| `COMPLETED` | Concluso positivamente, **dopo** che l'azione di fine processo è riuscita. |
| `REJECTED` | Concluso negativamente. |
| `CANCELLED` | Annullato (per esempio documento cancellato, o processo riavviato). |
| `FORWARDED` | Chiuso per passaggio di consegne a un altro processo (opzione HANDOFF). |
| `ERROR` | Bloccato: l'azione di fine processo è fallita, oppure l'esito di un handler è incerto. Serve un intervento (riprova o conferma dell'esito). |

Per ogni documento e tipo processo può esserci **un solo processo aperto**. Due avvii simultanei sullo stesso OdA vengono respinti dal database.

## 4.2 Stati del task

| Stato | Significato |
| --- | --- |
| `OPEN` | In attesa di qualcuno. |
| `EXECUTING` | Azione in corso: il motore sta chiamando gli handler. |
| `OK` / `KO` | Approvato / respinto. |
| `MOVED` | Spostato a un altro utente (al suo posto nasce un nuovo task). |
| `SKIPPED` | Saltato (soglia d'importo o istruzione di un handler). |
| `CANCELLED` | Annullato insieme al processo. |
| `ERROR` | Errore. |

## 4.3 L'avvio automatico

Le exit di salvataggio dell'OdA non avviano più direttamente il workflow (come faceva `/EWF/WF_START`). Al salvataggio, la BAdI `ME_PROCESS_PO_CUST~POST` chiama l'**estrattore** dell'OdA (`/EWF/CTX_PO`) e scrive in una **coda** (`/EWF/START_Q`) un evento con la **fotografia** del documento: se è nuovo, se esiste ancora, valore, attributi e posizioni. Tutto avviene nella stessa LUW del documento: se il salvataggio dell'OdA fallisce, l'evento non esiste.

> **Il punto di aggancio descrive, il motore decide.** La BAdI non sceglie se avviare, riavviare o annullare: riporta solo i fatti. La decisione la prende eWF, confrontando la fotografia con i processi del documento.

eWF legge la coda ogni 30 secondi. Il pulsante **Aggiorna** della lista delle attività anticipa la lettura: chi ha appena salvato un OdA lo vede in lista in pochi secondi, senza aspettare il giro successivo.

Per ogni evento (`TOUCHED`, "il documento è stato toccato") decide così:

| Situazione | Cosa fa eWF |
| --- | --- |
| Documento cancellato o senza posizioni attive | Annulla i processi aperti. |
| C'è un processo aperto | Confronta con la fotografia dell'avvio (vedi 4.4): riavvia se la modifica è rilevante, altrimenti prosegue aggiornando le descrizioni. |
| Documento nuovo | Applica le **regole di avvio** e avvia il processo (nulla se nessuna regola si applica). |
| Documento già approvato o respinto con eWF | Nuovo avvio solo se la modifica è rilevante rispetto all'ultimo processo concluso. |
| Documento tornato attivo dopo un annullamento per cancellazione | Nuovo avvio. |
| Documento esistente mai passato da eWF | Nulla (per esempio documenti precedenti all'avvio di eWF). |
| Ultimo processo annullato a mano o inoltrato | Nulla: la decisione presa dall'utente resta. |

Il motore accetta anche eventi già "decisi" dal sistema di origine (nuovo, rilascio azzerato, modificato, cancellato), utili per altri tipi documento o per la Public Edition; per l'OdA si usa l'evento neutro.

Le **regole di avvio** associano un tipo documento, eventualmente con condizioni sugli attributi (`*` = qualsiasi), a un tipo processo. Vince la priorità più alta, poi la regola più specifica. Il **richiedente** è l'utente SAP che ha salvato, tradotto nell'utente eWF corrispondente: se non ce n'è uno attivo, il processo parte lo stesso e i task del richiedente vanno al team di riserva.

Se un evento fallisce, per esempio perché SAP non risponde, resta in coda e si ritenta. Gli eventi successivi **dello stesso documento** aspettano dietro di lui; gli altri documenti proseguono.

## 4.4 Modifiche del documento durante l'approvazione

Se l'OdA viene modificato mentre è in approvazione, eWF confronta il documento **posizione per posizione** con la fotografia dell'avvio:

- **modifica rilevante:** una posizione aggiunta o eliminata, oppure quantità o valore oltre la tolleranza configurata sul tipo processo. Il processo viene **riavviato**: il documento si rilegge prima di annullare il processo in corso.
- **modifica non rilevante:** il processo prosegue, ma eWF **aggiorna la fotografia descrittiva** (testi e descrizioni), così lista e schede mostrano i dati attuali. Valore e posizioni approvati restano quelli dell'avvio.

Il confronto non si fa mai sui totali: variazioni opposte su posizioni diverse si annullerebbero e passerebbero inosservate.

## 4.5 Quando SAP non risponde

Il motore distingue tre situazioni, perché le conseguenze sono diverse:

| Situazione | Esito | Conseguenza |
| --- | --- | --- |
| SAP non raggiungibile **prima** della chiamata, o handler non disponibile | KO | L'azione non avviene, il task torna aperto, l'utente vede il motivo e può riprovare. |
| Errore **durante** la chiamata (rete, dump) | UNKNOWN | Non si sa se SAP abbia eseguito: il processo va in ERROR e un amministratore deve verificare e confermare l'esito. |
| Azione di fine processo (rilascio) fallita | ERROR | Il processo non risulta concluso finché il rilascio non riesce; si può ritentare. |

Le chiamate agli handler sono registrate con una chiave di idempotenza: se lo stesso handler viene richiamato con la stessa chiave, SAP non lo riesegue.

# 5. L'applicazione web

## 5.1 Accesso

- Ogni utente entra con il proprio utente eWF e una password. Il database conserva solo l'**impronta** della password, mai la password.
- Le password del legacy **non vengono migrate**. L'amministratore genera una password provvisoria dall'applicazione (capitolo 6), che al primo accesso va cambiata.
- Regole della password: almeno 10 caratteri, con lettere e numeri, diversa dal nome utente e dalla precedente. La password si cambia anche dal menu, indicando quella attuale.
- Dopo **5 tentativi errati** l'utente è bloccato per **15 minuti**; l'amministratore può sbloccarlo prima. Il messaggio d'errore è volutamente generico (non dice se è sbagliato l'utente o la password).
- La pagina di accesso ha lo stesso aspetto di quella del ticketing (immagine a sinistra, maschera a destra).

## 5.2 Il menu e la pagina iniziale

Dopo l'accesso l'utente vede, come nel ticketing:

- a sinistra le **funzioni abilitate**, una scheda per funzione: Lista attività, Auto-sostituzione, Sostituzioni, Processi a cui ho partecipato, Cambio password; per gli amministratori anche le funzioni del capitolo 6, su fondo di colore diverso. La scheda "Processi in errore" segnala in rosso quanti processi sono da sistemare;
- a destra la **sintesi** delle attività in carico: il totale e quante sono verdi, gialle e rosse secondo il semaforo, più quante arrivano da sostituzioni. Cliccando la sintesi si apre la lista.

Ogni funzione ha in alto il pulsante **« Menu** per tornare alla pagina iniziale. Il collegamento diretto a un task (dalla mail) apre subito il dettaglio, senza passare dal menu.

## 5.3 La lista delle attività

Ogni utente vede i task in attesa per tre motivi:

- **propri:** ne è il titolare;
- **come sostituto:** sono di un utente che oggi sostituisce, con l'indicazione "per conto di…";
- **di team:** sono di un team di cui fa parte.

Per ogni riga la lista mostra il tipo processo, il documento, il task, il richiedente, il valore, i giorni di attesa con un **semaforo** (verde, giallo dal 6° giorno, rosso dall'11°: soglie configurabili per tipo processo) e un **riepilogo** leggibile. Il riepilogo si compone da un modello configurato sul tipo processo, per esempio:

`{sap.LIFNR_TXT} {sap.LIFNR} - {sap.WERKS_TXT} {plant} - {sap.BSART}`

### Filtri per colonna

Sotto le intestazioni c'è una riga di campi, uno per colonna, come nel ticketing: si filtra scrivendo (senza distinguere maiuscole e minuscole) e "Togli filtri" li azzera. Quando i filtri lasciano un solo tipo processo compaiono anche le sue **colonne specifiche** (per esempio fornitore, divisione, gruppo acquisti), filtrabili anch'esse.

### Allegati, Commenti, Storico in riga

Ogni riga ha tre pulsanti, **Allegati**, **Commenti** e **Storico**, che aprono una finestra sopra la lista senza passare dal dettaglio. Un pulsante è attivo ed evidenziato, con il numero, solo se c'è qualcosa da vedere. Fa eccezione Allegati per i processi con gli allegati nel sistema di origine: contarli richiederebbe una chiamata a SAP per ogni riga, quindi il pulsante resta sempre attivo, senza numero (vedi il Registro delle decisioni).

Nella finestra si passa da una vista all'altra (Allegati, Commenti, Storico, Gantt). Commenti e allegati seguono le regole del dettaglio.

### Aggiorna

Il pulsante **Aggiorna** fa leggere subito la coda degli avvii da SAP (capitolo 4.3) e poi rilegge la lista; l'attesa è di pochi secondi. Se SAP non risponde, la lista si aggiorna comunque con i dati già presenti e un avviso lo segnala.

## 5.4 Chi può vedere un processo

Un utente può aprire il dettaglio di un processo se ne è il **richiedente**, oppure se ha o ha avuto **un task** su quel processo (come titolare, esecutore, chi l'ha preso in carico, chi l'ha spostato o chi l'ha ricevuto), oppure se è **membro del team** di uno dei task, oppure se è **sostituto oggi** di una di queste persone, oppure se vi ha scritto un **commento** o caricato un **allegato**.

Gli **amministratori** possono consultare qualsiasi processo.

Poter vedere non significa poter agire: chi può agire sul task lo decide il motore (titolare, sostituto attivo, membro del team; peso sufficiente; task non preso in carico da un altro).

## 5.5 Il dettaglio e le azioni

Il dettaglio di un processo mostra:

- **La tua attività**, solo se c'è un task su cui l'utente può agire: nota, Approva/Respingi oppure la scelta dell'opzione, Sposta, le funzioni speciali e, sotto la riga "Visualizza documento", la scelta della scheda, Apri PDF e Apri in SAP.
- **Documento:** i dati di testata e la fotografia del contesto.
- **Percorso:** gli step già fatti, quello in corso e quelli previsti. Il percorso si ferma al primo nodo di scelta non ancora deciso, perché oltre non è noto.
- **Storico:** chi ha fatto cosa e quando, con le note; il pulsante **Gantt** mostra lo stesso storico su una linea del tempo (5.7).
- **Allegati** e **Commenti**.

I riquadri si aprono chiusi, tranne "La tua attività", e il titolo indica quanti elementi contengono ("Commenti (2)", "Allegati — nessuno").

### Regole delle azioni

- Il **rifiuto** richiede una nota.
- Le azioni importanti chiedono conferma.
- Se due persone agiscono sullo stesso task nello stesso momento, o lo stesso utente fa doppio clic, la seconda azione viene **respinta** con un messaggio: il task è già stato modificato.
- Se un handler fallisce, per esempio se l'OdA è aperto in ME22N, l'utente vede **"Operazione non eseguita"** con il motivo e il task resta aperto. Se l'esito è incerto, l'utente lo vede ("Esito incerto") e il processo passa all'amministratore (capitolo 6).
- Un task configurato come **solo SAP GUI** è visibile ma non si completa dal web: il dettaglio lo segnala.

### Dettaglio in sola consultazione

Aperto da "Processi a cui ho partecipato" o dalla gestione processi, il dettaglio è **in sola consultazione**: non compaiono azioni, e non si aggiungono né si cancellano commenti e allegati. Il blocco vale anche nel server, non solo a video.

## 5.6 Commenti e allegati

I **commenti** sono note libere sul processo, visibili a chi può vedere il processo. Sono distinti dalle note delle azioni (OK/KO/sposta), che restano nello storico.

Per gli **allegati**, il tipo processo stabilisce dove stanno:

- **PROCESS:** nel database eWF. Si possono aggiungere più file insieme; può eliminarli solo chi li ha caricati, solo a processo aperto, e restano comunque nel database come cancellati.
- **DOCUMENT:** nel sistema di origine, per esempio gli allegati GOS dell'OdA. eWF li consulterà al momento, senza copiarli. L'interfaccia è pronta; mancano gli adattatori: RFC per R/3 e S/4 on-premise, OData per S/4 Public, dietro la stessa interfaccia.

La dimensione massima di un allegato è configurabile (default 20 MB).

## 5.7 Il Gantt del processo

Il Gantt mostra come si è svolto un processo su una linea del tempo:

- una **riga per persona** (o per team, finché nessuno ha preso in carico il task), nell'ordine in cui entra nel processo;
- ogni attività è una **barra** dall'arrivo all'esecuzione, nel colore dell'esito (approvato, respinto, spostato, in corso, errore, annullato); il rombo segna l'avvio; un'attività ancora aperta è tratteggiata e arriva fino a "adesso";
- accanto alle barre il nome dell'attività e la durata, dove c'è spazio; passando con il mouse su una barra compaiono attività, esito, date, "per conto di" e la nota.

> **Scala compressa:** lo spazio tra due eventi cresce con il logaritmo della durata, così un'approvazione di un minuto resta leggibile anche in un processo durato settimane. Le attese lunghe sono accorciate e segnate con `//`, con la durata scritta sotto l'asse. La scala quindi non è proporzionale: le durate vanno lette nei testi, non nelle lunghezze.

## 5.8 Processi a cui ho partecipato

Elenco, in sola consultazione, dei processi in cui l'utente ha avuto una parte: li ha **avviati**, ha **agito su un task** (anche come sostituto), vi ha scritto un **commento** o caricato un **allegato**.

- Filtro **In corso / Conclusi / Tutti** e filtri per colonna; al massimo i 500 processi più recenti.
- Per ogni processo: tipo, documento, riepilogo, stato, date di avvio e di chiusura, chi l'ha avviato, **il mio ruolo** (per esempio "avviato, approvato, commentato") e **dove si trova** (le attività ancora aperte).
- In riga Allegati, Commenti e Storico (con il Gantt), e **Apri** per il dettaglio in sola consultazione.

## 5.9 Auto-sostituzione e Sostituzioni

- **Auto-sostituzione:** l'utente abilitato indica il proprio sostituto e il periodo (estremi inclusi); vede la sostituzione in corso o programmata e chi sta sostituendo lui. "Rimuovi sostituzione" la toglie da oggi. Chi non è abilitato vede un avviso: le sue sostituzioni le gestisce l'amministratore.
- **Sostituzioni:** consultazione di chi mi sostituisce e di chi sto sostituendo, in corso e programmate.

## 5.10 Le schede PDF

Dal dettaglio si apre una **scheda PDF** del documento, pensata come "foglio di lavoro" per l'approvazione, non come stampa ufficiale:

- **Scheda OdA:** testata, fornitore, condizioni, posizioni con valori e consegne, letti da SAP al momento (`BAPI_PO_GETDETAIL1`), più le approvazioni, i prossimi passi e i commenti.
- **Riepilogo approvazione:** solo i dati di eWF; è sempre disponibile, anche senza SAP.

Le stampe specifiche dei clienti si aggiungeranno come ulteriori viste dello stesso meccanismo.

## 5.11 Le notifiche mail

- **Avviso immediato:** per i task configurati così, mail al titolare (o al suo sostituto di oggi; per i task di team a tutti i membri) quando il task gli viene assegnato, con il collegamento diretto al task.
- **Riepilogo periodico:** ogni N giorni per utente, nei giorni lavorativi, l'elenco delle attività in attesa. Chi non ha attività non riceve nulla; chi oggi è sostituito non riceve il suo riepilogo.
- **Avviso di sostituzione:** quando un utente indica il proprio sostituto, il sostituto riceve una mail con il periodo e le attività già in attesa.
- I testi sono modelli modificabili nel database. Le mail partono da una coda: se il server di posta non è configurato o non risponde, restano in coda e partono dopo.

# 6. L'amministrazione

Sono **amministratori** i membri attivi del team di riserva del motore (default `WFADM`): gli stessi che ricevono i task rimasti senza titolare. Le loro funzioni compaiono nel menu su fondo di colore diverso.

> **Deve restare sempre almeno un amministratore attivo.** Una modifica di team o di utenti che lascerebbe il team vuoto viene rifiutata per intero; un amministratore non può togliersi da solo dagli amministratori né disattivarsi.

## 6.1 Processi in errore

Elenco dei processi bloccati, con il motivo e l'attività coinvolta. Due casi hanno un'azione:

- **Esito incerto di una chiamata a SAP** (per esempio interruzione di rete durante il rilascio): l'amministratore verifica il documento in SAP e registra cosa risulta, "eseguita" oppure "non eseguita", con una **nota obbligatoria**. Il processo prosegue come se la chiamata fosse andata a buon fine, oppure il task torna a chi doveva farlo.
- **Azione di fine processo non riuscita** (per esempio OdA bloccato in modifica): tolta la causa in SAP, "Riprova azione finale".

Per gli altri errori la pagina mostra il dettaglio; serve l'intervento tecnico.

## 6.2 Gestione processi

Tutti i processi, con la stessa consultazione di "Processi a cui ho partecipato" (filtri, Allegati, Commenti, Storico, Gantt, dettaglio in sola consultazione) e una **ricerca** per documento, processo, testo o richiedente.

I processi in corso hanno il pulsante **Annulla**: chiede un **motivo obbligatorio**, che finisce nell'audit; i task aperti spariscono dalle liste.

> **L'annullamento non tocca SAP:** il documento resta com'è (per esempio l'OdA ancora bloccato) e va gestito lì. Un'azione automatica verso SAP all'annullamento non è prevista: cancellare o rilasciare il documento sono decisioni di business, non di amministrazione di eWF (vedi il Registro delle decisioni).

## 6.3 Avvio forzato

Avvia un processo per un documento che non è arrivato dalla coda, o dopo un annullamento: si sceglie il **tipo documento**, poi il **processo** tra quelli **ammessi** per quel tipo (non sospesi e con una versione attiva), poi il **documento**. Il motore legge il documento nel sistema di origine e rifiuta doppioni e documenti inesistenti. Nell'audit l'amministratore risulta come chi ha avviato.

## 6.4 Sostituzioni di tutti

Elenco delle sostituzioni in corso e programmate di ogni utente; l'amministratore ne aggiunge per chiunque (titolare, sostituto, periodo) e le toglie, con le stesse regole del capitolo 3.6. Resta registrato quale amministratore le ha decise.

## 6.5 Utenti

- **Nuovo utente:** login (minuscolo, unico, non modificabile dopo), nome, e-mail, utente SAP, classe utente, permesso di auto-sostituzione (sì / no / come la classe). Nasce attivo e senza password.
- **Modifica** dei dati essenziali e **attivo/disattivo**. Un utente disattivato non entra e non riceve più task: i suoi task futuri vanno al team di riserva.
- **Password provvisoria:** generata dall'applicazione e mostrata **una sola volta**, da comunicare all'utente; va cambiata al primo accesso. Toglie anche il blocco.
- **Sblocca:** toglie il blocco dopo troppi tentativi errati, senza cambiare la password.
- **Team dell'utente:** quelli a cui appartiene, con aggiunta e uscita.

## 6.6 Team

- Elenco dei team con il numero di membri attivi; creazione (codice maiuscolo, non modificabile dopo) e descrizione.
- Per ogni team, i membri con validità dal / al e stato (attivo, futuro, concluso). Le date vuote significano "da sempre" e "senza scadenza".
- **Togli** chiude l'appartenenza a ieri (resta nello storico) oppure, se doveva ancora iniziare, la cancella. Le attività già arrivate al team restano al team: la modifica vale da quel momento in avanti.

I team sono **organizzazione** e si gestiscono qui; le definizioni dei processi (il futuro modulo di disegno) li **usano** scegliendoli da questo elenco.

# 7. Scenario di riferimento: l'ordine d'acquisto

Il processo `EONE_ODA` è quello usato per le prove. La tabella riporta la definizione di riferimento usata anche nei test automatici del motore; quella effettiva del sistema DEV deriva dalla migrazione della configurazione `/EWF/` e va confrontata con questa.

| Step | Task | Ruolo / assegnazione | Peso | Soglia | Se OK / se KO |
| --- | --- | --- | --- | --- | --- |
| 10 | Avvio processo Autorizz. ODA | Richiedente (STARTER) | 0 | – | → 20 / fine |
| 20 | Rilascio Responsabile Acquisti | RESPONSABILE_ACQ (regole) | 100 | – | → 30 / → 10 |
| 30 | 2° Rilascio Responsabile Acquisti | RESPONSABILE_ACQ_L1 (regole) | 200 | ≥ 10.000 EUR | → 40 / → 10 |
| 40 | 3° Rilascio Responsabile Acquisti | RESPONSABILE_ACQ_L2 (regole) | 200 | ≥ 50.000 EUR | fine / → 10 |

Sui rilasci (step 20–40) c'è l'aggancio `BEFORE_ACTION` `/EWF/WF_CHECK_LOCK_EKKO`, che impedisce di approvare o respingere mentre l'OdA è aperto in modifica. L'azione di fine processo è `/EWF/WF_RIL_ODA` con codice di rilascio `01`.

## 7.1 Lo svolgimento

1. Il buyer salva l'OdA in ME21N. La BAdI scrive in coda l'evento con la fotografia del documento (nuovo, valore, attributi, posizioni).
1. eWF legge la coda, usa la fotografia dell'evento (valore, attributi, descrizioni da `/EWF/CTX_PO`), trova la regola di avvio e crea il processo. Lo step 10 va al buyer.
1. Il buyer conferma l'avvio. Lo step 20 va al responsabile acquisti trovato dalle regole (per divisione e società); se il valore è sotto 10.000 EUR, gli step 30 e 40 non vengono eseguiti.
1. Il responsabile apre il task dalla lista delle attività (oppure dal collegamento diretto nella mail), consulta la scheda OdA e approva. Prima dell'approvazione eWF verifica in SAP che l'OdA non sia bloccato.
1. All'ultima approvazione il processo passa in `COMPLETING`, eWF chiama il rilascio in SAP e, solo se riesce, chiude il processo come `COMPLETED`.
1. Se un approvatore respinge (con nota), il processo torna allo step 10: il buyer può correggere l'OdA. Se la modifica è rilevante, il processo riparte da capo.

> **Verificato sul DEV:** avvio da coda, descrizioni nel contesto, lista con riepilogo, dettaglio, percorso, storico, allegati, scheda OdA, primo rilascio, blocco dell'azione quando SAP non risponde. **Da completare:** prove sistematiche dei rilasci successivi, del rifiuto con ritorno al richiedente e del rilascio finale in SAP.

# 8. Punti aperti

| Tema | Stato |
| --- | --- |
| Presa in carico dei task di team | Prevista dal motore, non ancora disponibile nell'applicazione web. |
| Controllo di budget | Il controllo dell'ordinato annuo per fornitore di `WF_USER_TASK` non è ancora implementato. |
| Allegati nel sistema di origine | Interfaccia pronta, adattatori da scrivere: GOS via RFC (R/3, S/4 on-premise) e `API_CV_ATTACHMENT_SRV` via OData (Public Edition), insieme all'adattatore OData del documento. |
| Apri in SAP | Collegamento SAP GUI (`/EWF/VIEWDOC` e file .sap) previsto, non implementato. |
| Azione all'annullamento | Oggi nessuna. Se un cliente la chiede (per esempio il flag di cancellazione sulle posizioni dell'OdA), andrà configurata per tipo processo, come le azioni di fine processo. |
| Accesso a due fattori (MFA) | Da riprendere dall'applicazione di ticketing. |
| Public Edition | Il rilascio classico non esiste: il "fermo" del documento si fa con il flexible workflow a un solo step, assegnato a un utente tecnico eWF. Da definire come eWF chiude il passo da fuori. |
| Disegno dei processi | Le definizioni oggi si caricano da strumenti tecnici. Prossimo modulo: modifica dall'applicazione con diagramma del flusso. |
| Storico delle appartenenze ai team | Un utente ha una sola appartenenza per team: se esce e rientra, il nuovo periodo sostituisce il vecchio. |

# Appendice – Corrispondenze con il legacy /EWF/

| Legacy /EWF/ | Nuovo eWF |
| --- | --- |
| `PROCESSO`, `PROCTYPE` | Tipo processo e versione |
| `TASK` (`Z_WF_STEP`, `Z_WF_SUBOP`) | Step e task della definizione |
| `PROCDOC`, `PROCRUN` | Processo (istanza) |
| `TASKRUN` | Task (istanza) |
| `USER0` | Utenti (`app_user`), senza password legacy |
| `USER_TASK`, `/EWF/WF_USER_TASK` | Regole di assegnazione, risolutore per specificità |
| `SOSTITUTI` | Sostituti (auto-sostituzione e sostituzione decisa dall'amministratore) |
| `FM_EXANTE`, `FM_OK`, `FM_KO`, `FM_EXPOST`, `FM_BUTTON01` | Agganci `BEFORE_ACTION`, `ON_OK`, `ON_KO`, `ON_ACTIVATE`, `BUTTON` |
| `Z_WF_FM_END_PROC`, `Z_WF_SET_RIL` | Azione di fine processo e suo parametro |
| `Z_WF_LIMIT` | Soglia d'importo dello step |
| `Z_WF_LIV`, `PUNTEGGIO` | Peso richiesto e peso dell'utente |
| `WF_SELEZIONE_OPZIONE`, processi fittizi | Nodi di scelta, rami, opzioni che avviano processi |
| `/EWF/WF_START` nelle exit | Coda degli avvii `/EWF/START_Q` e regole di avvio |
| `/EWF/WF_RESTART_PROCESS` | Riavvio |
| `STEP_OK/KO` = 99999 | Step successivo vuoto = fine processo |
| `/EWF/SEND_MAIL_NEW_TASK` | Avviso immediato e riepilogo periodico |
