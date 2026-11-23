# eWF – Dal prototipo alla produzione

**Destinatari:** gruppo di sviluppo e-One; responsabili di progetto presso il cliente.

**Stato:** bozza 0.2 – 23 novembre 2026.

**Novità della 0.2:** nuovo capitolo 10 sull'accesso da fuori rete (telefono, tablet, Internet), MFA tra le voci indispensabili in quel caso, problemi di rete visti nelle prove; segnate come **«Fatto nel prototipo»** le voci di sviluppo già realizzate (pool, job, processi in errore, utenti, sostituti, link nelle mail, Apri in SAP, allegati SAP).

**Da leggere insieme a:** Analisi funzionale; Architettura e base dati.

# 1. Scopo e ipotesi

Il prototipo dimostra che eWF funziona dall'inizio alla fine su un processo reale (ordini d'acquisto, sistema DEV S/4HANA Private). Questa guida elenca **cosa manca per farlo usare ogni giorno a un cliente**, cosa decidere e in che ordine.

È un percorso con un inizio e una fine, diverso dalla guida di esercizio (che spiegherà come si gestisce eWF quando è già in produzione).

## 1.1 Ipotesi di partenza

- eWF gira **presso il cliente**, su un suo server Windows o Linux; dove le due piattaforme differiscono, la guida lo indica.
- Non è ancora stato scelto il primo cliente: il riferimento concreto è il sistema DEV usato per il prototipo.
- Il primo tipo documento in produzione è l'ordine d'acquisto; il cliente arriva dal vecchio eWF ABAP.

## 1.2 Come leggere le priorità

| Priorità | Significato |
| --- | --- |
| **I** – Indispensabile | Senza questa voce non si va in produzione. |
| **C** – Consigliata | Si può partire senza, ma con un rischio o un costo di gestione da accettare consapevolmente. |
| **P** – Più avanti | Miglioramento da pianificare dopo l'avvio, o solo se il cliente lo richiede. |

In fondo al documento c'è la **lista di controllo** con tutte le voci, una colonna per il responsabile e una per lo stato.

# 2. Cosa significa «produttivo»

eWF è pronto per la produzione quando, per ciascuna di queste domande, c'è una risposta scritta e provata:

1. **Funziona?** I processi del cliente sono stati provati da chi li usa, compresi i casi di errore (SAP non raggiungibile, OdA bloccato, rifiuto, riavvio).
1. **È sicuro?** Nessuna password in chiaro, accesso solo cifrato (HTTPS), permessi minimi verso database e SAP.
1. **Si recupera?** Esiste un backup, ed è stato **ripristinato almeno una volta** per prova.
1. **Si vede quando qualcosa non va?** Qualcuno riceve un avviso se la coda si ferma, se le chiamate a SAP falliscono o se le mail non partono.
1. **Si sa chi interviene?** Il cliente sa chi chiamare; e-One ha una procedura scritta per i guasti più probabili.
1. **Si torna indietro?** Se il primo giorno va male, c'è un modo concordato per tornare al vecchio eWF.

# 3. Infrastruttura e ambienti

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Tre ambienti separati** | Sviluppo, test (collaudo) e produzione, ognuno con il suo database e collegato al suo sistema SAP (DEV, QAS, PRD). Mai un'applicazione di test collegata al database o al SAP di produzione. |
| I | **Tomcat come servizio** | In produzione Tomcat si avvia come servizio del sistema operativo (servizio Windows o systemd su Linux), parte da solo al riavvio del server e non dipende da un utente collegato. Il BAT di avvio resta per lo sviluppo. |
| I | **HTTPS** | Accesso solo cifrato. Due strade: certificato direttamente in Tomcat, oppure un server davanti (IIS, nginx, Apache) che gestisce il certificato e inoltra a Tomcat. La seconda è preferibile se il cliente ha già un server web gestito dal suo IT. |
| I | **Java e Tomcat in versione supportata** | Stessa versione di Java usata per compilare (21) o superiore; Tomcat 11 aggiornato. Annotare le versioni installate in ogni ambiente. |
| C | **Server dimensionato** | Per i volumi di eWF basta un server modesto (2–4 CPU, 8 GB di RAM per Tomcat e PostgreSQL insieme). Separare database e Tomcat su due macchine solo se il cliente lo chiede o se il database è condiviso con altre applicazioni. |
| C | **Orario e fuso** | Server sincronizzato (NTP) e fuso Europe/Rome: scadenze, semafori e riepiloghi dipendono dall'ora. |
| P | **Più istanze di Tomcat** | Il codice lo supporta già (i job girano su un solo nodo grazie a un lock del database). Serve solo per alta disponibilità. |

# 4. Base dati

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Pool di connessioni** | **Fatto nel prototipo.** Pool HikariCP creato da `EwfApp` alla partenza. Resta da tarare il numero di connessioni (partenza: 10–20) dopo la prova di carico. |
| I | **Script unico dei permessi** | Un solo script che crea i ruoli `ewf_owner` e `ewf_app` e assegna a `ewf_app` tutti e soli i permessi necessari, da rieseguire in ogni ambiente. Oggi i permessi sono sparsi negli script di migrazione e in parte dati a mano sul DEV. |
| I | **Installazione da script** | Database nuovo: schema completo (`ewf_schema_v0.16.sql`), schema di eone-connect (`ConnectSchemaTool`), script dei permessi, configurazione. Tutto come `ewf_owner`, mai come amministratore. |
| I | **Backup e prova di ripristino** | Backup giornaliero (`pg_dump` o il sistema di backup del cliente) conservato fuori dal server. Il backup vale solo se è stato **ripristinato per prova** in un ambiente separato. Il database contiene anche gli allegati: va considerato nel dimensionamento. |
| I | **Password dei ruoli** | Password robuste e diverse per ambiente, conservate dove il cliente conserva le credenziali di servizio; mai nei file del progetto. |
| C | **Manutenzione** | PostgreSQL fa da solo la pulizia ordinaria (autovacuum): verificare che sia attiva. Controllare ogni tanto la crescita delle tabelle `attachment_content`, `audit_event`, `handler_execution`. |
| C | **Conservazione dei dati** | Decidere con il cliente per quanto tempo si tengono processi conclusi, allegati e audit (es. 10 anni per i documenti contabili collegati). Oggi non c'è nessuna cancellazione automatica: è il comportamento più prudente, ma va scritto. |
| P | **Catena di hash dell'audit** | Solo se il cliente chiede valore probatorio delle approvazioni (revisori, certificazioni). |

# 5. Collegamento a SAP

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Utente RFC tecnico** | Un utente di sistema (tipo «System» o «Comunicazione», non di dialogo) dedicato a eWF, con autorizzazioni minime: `S_RFC` sui soli gruppi funzione `/EWF/` e sulle BAPI usate dalle schede, più quelle richieste dalle FM legacy (es. rilascio OdA). Password solo nella variabile `EWF_SAP_<DEST>_PASSWD`. |
| I | **Destinazioni per ambiente** | Un file `<DEST>.properties` per ambiente (DEV, QAS, PRD) nella cartella indicata da `EWF_SAP_CONFIG_DIR`. Nessuna password nel file. |
| I | **JCo installato in Tomcat** | `sapjco3.jar` in `tomcat/lib`; libreria nativa (`sapjco3.dll` su Windows, `libsapjco3.so` su Linux) in una cartella del percorso di sistema. Versione di JCo annotata. |
| I | **Trasporto degli oggetti ABAP** | Tutti gli oggetti `/EWF/` nuovi o modificati (dispatcher, coda degli avvii, estrattori, BAdI, `DOC_INFO`, `CTX_PO`) in un ordine di trasporto unico e documentato, provato prima in QAS. |
| I | **Attivazione per tipo documento** | In `/EWF/START_CFG` il tipo documento passa al motore Java (`J`) solo il giorno del passaggio; fino ad allora resta al legacy. Si può tornare indietro rimettendo `L`. |
| C | **Raggiungibilità di rete** | Dal server di eWF verso SAP devono essere aperte le porte del gateway (33nn) o del message server, secondo il collegamento scelto. Da verificare con l'IT del cliente prima del collaudo. **Collegamento stabile:** nelle prove (21/11) la VPN verso il DEV è caduta più volte (`WSAETIMEDOUT` verso la porta 3301): eWF ha retto, ma in produzione il server di eWF deve stare nella stessa rete di SAP o su un collegamento permanente, non su una VPN personale. |
| C | **Controllo dei blocchi** | Verificare che l'aggancio `/EWF/WF_CHECK_LOCK_EKKO` sia presente su tutti i task di rilascio: evita di approvare un OdA aperto in modifica. |

# 6. Migrazione dal vecchio eWF

È il capitolo più delicato: il cliente ha processi aperti nel vecchio eWF, e quei processi non devono né perdersi né raddoppiarsi.

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Migrazione della configurazione** | Processi, step, task, regole di assegnazione, utenti, team, sostituti si portano con `MigrationTool` dalle tabelle `/EWF/`. Senza l'opzione `--apply` lo strumento fa una **prova generale**: scrive e valida tutto, produce un rapporto e poi annulla. Ogni migrazione va **riprovata più volte** in test e confrontata a mano su un campione di processi. |
| I | **Utenti e password** | Le password del legacy non si migrano. Prima dell'avvio ogni utente riceve una password provvisoria (con un canale sicuro, non nella stessa mail del link) e la cambia al primo accesso. Verificare indirizzi mail e collegamento all'utente SAP. |
| I | **Processi in corso al passaggio** | Decisione da prendere con il cliente. Strada consigliata: **esaurimento**. I documenti con processo aperto nel vecchio eWF finiscono lì; i documenti nuovi o modificati dopo il passaggio vanno al nuovo. Alternativa: migrare anche i processi aperti; è possibile ma richiede uno sviluppo e una verifica dedicati. |
| I | **Piano di ritorno** | Scritto prima del passaggio: in quali casi si torna al vecchio eWF, chi lo decide, cosa si fa dei processi nati nel nuovo nel frattempo. |
| C | **Comunicazione agli utenti** | Mail e breve formazione: nuovo indirizzo, primo accesso, cosa cambia rispetto a prima (lista nel browser, niente SAP GUI per approvare). |

# 7. L'applicazione

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Versioni di rilascio** | Oggi tutti i moduli sono in versione di sviluppo (`0.1.0-SNAPSHOT`). Per la produzione si fissa una versione (es. `1.0.0`) su tutti i moduli, si compila da Git con un tag e si archivia il `war` prodotto. Così si sa sempre cosa è installato e si può ricostruirlo. |
| I | **Pom aggregatore** | Un progetto padre che compila i cinque moduli nell'ordine giusto con un solo comando. Elimina l'errore più frequente di questi giorni: un modulo non reinstallato. |
| I | **Deploy senza il designer** | In produzione si installa il `war` in Tomcat (cartella `webapps` o console di gestione). Il designer CaptainCasa resta uno strumento di sviluppo. |
| I | **Configurazione esterna** | `config.properties` fuori dal `war`, per ambiente; segreti nelle variabili d'ambiente del servizio. Un esempio commentato è già nel progetto. |
| I | **Log su file** | Oggi i messaggi vanno sulla console di Tomcat. In produzione servono file di log con data, livello (informazione, avviso, errore), rotazione giornaliera e conservazione limitata. Nessuna password o dato sensibile nei log. **Da correggere:** quando SAP non risponde, i job ripetono lo stesso errore a ogni giro e riempiono il log; serve una riga sola alla caduta e una al ritorno del collegamento. |
| C | **Pagina di stato** | Un indirizzo che risponde «tutto bene» o dice cosa non va (database, SAP, ultimo giro della coda), utile al monitoraggio del cliente. |
| C | **Numero di versione visibile** | Versione dell'applicazione nella pagina di logon o in un «Informazioni su»: indispensabile quando il cliente segnala un problema. |
| P | **Icona dell'applicazione** | Oggi il browser chiede `icon.png` e riceve un errore 404. Icona e titolo (anche per «aggiungi a schermata Home» sul telefono). |

# 8. Funzioni che mancano per l'avvio

Il prototipo copre il lavoro dell'utente. Mancano alcune funzioni di «servizio», senza le quali la produzione non regge.

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Job dentro l'applicazione** | **Fatto nel prototipo.** La coda degli avvii, le notifiche, il riepilogo periodico e la spedizione delle mail girano dentro l'applicazione web (`JobHost`): partono con Tomcat e si fermano con lui. |
| I | **Gestione dei processi in errore** | **Fatto nel prototipo.** Pagina «Processi in errore» con «Riprova azione finale» e «conferma esito» per le chiamate dall'esito incerto. Corretto il 23/11 un errore all'apertura del dettaglio: da riprovare. |
| I | **Gestione utenti e password** | **Fatto nel prototipo.** Pagina «Utenti»: creare e disattivare, reimpostare la password, sbloccare, assegnare ai team. Resta da DB la classe utente (decisione del 21/11). |
| I | **Link diretto nelle mail** | **Fatto nel prototipo.** Il link «apri il task» nelle mail di avviso usa `EWF_APP_BASE_URL`: deve essere l'indirizzo con cui gli utenti raggiungono eWF (vedi capitolo 10 se si apre da fuori rete). |
| C | **Presa in carico dei task di team** | Prevista dal motore, manca il pulsante nell'applicazione. Indispensabile solo se il cliente usa task di team. |
| C | **Gestione dei sostituti dall'applicazione** | **Fatto nel prototipo.** Ogni utente indica il proprio sostituto per un periodo; l'amministratore vede e corregge tutti i sostituti. |
| C | **Apri in SAP** | **Fatto nel prototipo.** Collegamento SAP GUI dal dettaglio (`/EWF/VIEWDOC`). |
| P | **Allegati GOS** | **Fatto nel prototipo.** Allegati GOS e ArchiveLink dell'OdA visibili e scaricabili da eWF, insieme a quelli del processo; conteggio sul pulsante. Manca il caricamento verso SAP. |
| P | **Controllo di budget** | Il controllo sull'ordinato annuo per fornitore del legacy, se il cliente lo usava. |

# 9. Sicurezza

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Segreti nel servizio** | Le variabili `EWF_DB_PASSWORD`, `EWF_SAP_<DEST>_PASSWD`, `EONE_SMTP_PASSWORD` si impostano nella configurazione del servizio Tomcat (Windows: parametri del servizio; Linux: file d'ambiente leggibile solo dall'utente del servizio). Accesso al server limitato agli amministratori. |
| I | **HTTPS e intestazioni di sicurezza** | Con HTTPS attivo si abilita anche l'intestazione HSTS (`EWF_SECURITY_HSTS`), che impedisce al browser di tornare in chiaro. |
| I | **Account di amministrazione** | Nessun utente di prova o di sviluppo (es. EONE) attivo in produzione con privilegi. Utenti di amministrazione nominativi. |
| C | **Accesso a due fattori (MFA)** | Consigliato anche in rete interna; **indispensabile** se eWF è raggiungibile da fuori rete (capitolo 10). Già realizzato nell'applicazione di ticketing: da riportare in eWF. |
| C | **Sessione singola** | Un utente collegato una sola volta: come nel ticketing. |
| C | **Dati personali (GDPR)** | eWF tratta nomi, mail e azioni dei dipendenti. Indicarlo nel registro dei trattamenti del cliente, con i tempi di conservazione decisi al capitolo 4. |
| C | **Aggiornamenti** | Piano per aggiornare Java, Tomcat, PostgreSQL e le librerie quando escono correzioni di sicurezza. |

# 10. Accesso da fuori rete (telefono, tablet, Internet)

Nasce dalla versione mobile (pagine semplificate per vedere i propri task, aprirli e approvare dal telefono). Le pagine si possono scrivere e provare in rete interna; **prima di renderle raggiungibili da fuori** vanno prese le decisioni di questo capitolo, insieme all'IT del cliente. Lo stesso vale per il link nelle mail aperto da casa o da un telefono.

## 10.1 La decisione di partenza: VPN o pubblicazione

|  | VPN aziendale sul dispositivo | Pubblicazione su Internet |
| --- | --- | --- |
| **Come funziona** | Il telefono entra nella rete del cliente; eWF resta un'applicazione interna. | eWF ha un indirizzo pubblico (es. `ewf.cliente.it`), raggiungibile da qualsiasi rete. |
| **Esposizione** | Nessuna: il server non è visibile da fuori. | Il server è esposto: servono proxy, certificato pubblico, MFA, aggiornamenti puntuali. |
| **Per l'utente** | Deve attivare la VPN prima di aprire eWF (o il link della mail). | Apre il link e basta, dopo l'accesso con MFA. |
| **Chi decide / gestisce** | IT del cliente (licenze VPN, profili sui telefoni, spesso un MDM). | IT del cliente (DNS, firewall, certificato) ed e-One (MFA, configurazione). |
| **Quando sceglierla** | Pochi approvatori fuori sede; il cliente ha già la VPN sui telefoni. | Molti approvatori in mobilità; si vuole che il link della mail funzioni ovunque. |

> Consiglio: **iniziare con la VPN** se il cliente l'ha già, perché non richiede nulla di nuovo sul server; passare alla pubblicazione solo quando MFA e proxy sono pronti e provati.

## 10.2 Voci

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Scelta VPN o pubblicazione** | Decisione scritta con l'IT del cliente (tabella 10.1). Determina tutte le voci seguenti: con la sola VPN restano necessarie HTTPS e le voci del capitolo 9, ma non il proxy pubblico. |
| I | **MFA prima di pubblicare** | Nessuna pubblicazione su Internet senza un secondo fattore. Due strade, da scegliere per cliente: **accesso con l'identità aziendale** (OIDC con Microsoft Entra ID, Google Workspace…), dove MFA e criteri restano al cliente ed eWF associa l'identità al proprio utente; oppure **codice TOTP** (app Authenticator) gestito da eWF, partendo da quanto già fatto nel ticketing. Per la seconda servono anche reset del dispositivo perso e codici di emergenza. |
| I | **Reverse proxy e certificato pubblico** | nginx, IIS (ARR) o Apache davanti a Tomcat: certificato pubblico con rinnovo automatico, solo TLS 1.2 o superiore, inoltro dell'intestazione `X-Forwarded-Proto` (eWF la usa per HSTS). Tomcat ascolta solo verso il proxy, mai direttamente su Internet. Timeout del proxy compatibili con le operazioni più lunghe (chiamate a SAP). |
| I | **Indirizzo pubblico nelle mail** | `EWF_APP_BASE_URL` uguale all'indirizzo che gli utenti usano davvero; se convivono interno ed esterno, un solo nome DNS che funzioni in entrambi i casi. |
| I | **Funzioni di amministrazione** | Decidere se designer, regole, utenti e processi in errore devono essere usabili da fuori rete. Consigliato: **no**. Poiché tutte le pagine passano dallo stesso indirizzo, il limite va realizzato nell'applicazione (es. funzioni di amministrazione solo se l'accesso arriva dalla rete interna, informazione fornita dal proxy): sviluppo da prevedere. |
| C | **Sessioni sul telefono** | Scadenza della sessione più breve per gli accessi da fuori; cookie di sessione `Secure`, `HttpOnly`, `SameSite`. Logout esplicito ben visibile nelle pagine mobili. |
| C | **Protezione dai tentativi di accesso** | Il blocco dopo 5 tentativi errati c'è già. Da fuori rete valutare anche il limite di richieste sul proxy e un avviso all'amministratore in caso di molti blocchi. |
| C | **Allegati scaricati sul telefono** | Gli allegati (offerte, contratti) finiscono nella memoria del dispositivo. Il cliente decide se è accettabile o se servono regole del suo sistema di gestione dei dispositivi (MDM). |
| C | **Registro degli accessi** | Log degli accessi del proxy conservato e consultabile (chi, quando, da dove), coerente con le regole GDPR del capitolo 9. |
| P | **Prova di sicurezza** | Prima della pubblicazione, una verifica delle vulnerabilità (anche solo con uno strumento automatico) sull'indirizzo pubblico. |

# 11. Test e collaudo

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Piano di prova funzionale** | Elenco di casi scritti, con l'esito atteso: avvio, approvazione a ogni livello e soglia, rifiuto e ritorno al richiedente, modifica rilevante e non rilevante, cancellazione, sostituto, spostamento, OdA bloccato, SAP spento, rilascio finale verificato in ME23N. |
| I | **Collaudo con il cliente** | In QAS, con utenti reali del cliente e i loro documenti tipici. Firma del collaudo prima del passaggio. |
| C | **Prova di carico leggera** | Simulare il picco del mattino (decine di utenti che aprono la lista insieme) e una coda di qualche centinaio di eventi, per verificare tempi e pool. |
| C | **Prova di guasto** | Spegnere SAP, spegnere il database, riavviare Tomcat durante un'approvazione: verificare che eWF si comporti come descritto nell'analisi funzionale e che nulla vada perso. |

# 12. Esercizio e monitoraggio

Cosa controllare, e come. Le query sono già nel documento Architettura e base dati, capitolo 4.11.

| Prior. | Voce | Cosa fare e perché |
| --- | --- | --- |
| I | **Controllo quotidiano** | Processi in `ERROR`; chiamate a SAP in `KO` o `UNKNOWN` (`handler_execution`); eventi della coda in errore (`/EWF/START_Q`, stato E); mail in `ERROR` o `DEAD` (`mail_outbox`); job falliti (`job_run`). |
| I | **Avvisi automatici** | Chi riceve una mail se un job fallisce più volte o se la coda non viene elaborata da più di N minuti. Inizialmente anche un controllo manuale quotidiano va bene, purché qualcuno sia incaricato. |
| I | **Procedura dei guasti** | Una pagina per ciascun caso probabile: SAP non raggiungibile, database pieno o fermo, certificato scaduto, utente bloccato, processo in errore. Cosa si vede, cosa si fa, chi chiamare. |
| C | **Accordo di assistenza** | Chi interviene, in che orari, con quali tempi; cosa fa il cliente da solo e cosa e-One. |

# 13. Ordine di lavoro suggerito

1. **Prima di tutto lo sviluppo che manca:** log su file (senza ripetizioni quando SAP non risponde), versioni di rilascio e pom aggregatore. Già fatti nel prototipo: job, pool, processi in errore, utenti, sostituti, link nelle mail.
1. **Poi l'ambiente di test presso il cliente:** server, HTTPS, servizio Tomcat, database installato da script, utente RFC, trasporto ABAP in QAS.
1. **Se serve l'accesso da fuori rete:** decisione VPN o pubblicazione con l'IT del cliente; con la pubblicazione, MFA e proxy pronti e provati prima di aprire l'indirizzo (capitolo 10). Le pagine mobili si collaudano intanto in rete interna.
1. **Migrazione di prova** della configurazione legacy in QAS, ripetuta finché i confronti tornano.
1. **Piano di prova e collaudo** con il cliente; prove di guasto e di carico.
1. **Preparazione al passaggio:** produzione installata, backup provato, procedure scritte, password provvisorie, comunicazione agli utenti, piano di ritorno.
1. **Passaggio** per tipo documento (`/EWF/START_CFG`), con esaurimento dei processi legacy e controllo quotidiano rafforzato nelle prime settimane.

# Lista di controllo

Tutte le voci della guida. Da compilare per ogni cliente.

| Prior. | Cap. | Voce | Responsabile | Stato |
| --- | --- | --- | --- | --- |
| I | 3 | Tre ambienti separati |  |  |
| I | 3 | Tomcat come servizio |  |  |
| I | 3 | HTTPS |  |  |
| I | 3 | Java e Tomcat in versione supportata |  |  |
| I | 4 | Pool di connessioni |  | Fatto nel prototipo |
| I | 4 | Script unico dei permessi |  |  |
| I | 4 | Installazione da script |  |  |
| I | 4 | Backup e prova di ripristino |  |  |
| I | 4 | Password dei ruoli |  |  |
| I | 5 | Utente RFC tecnico |  |  |
| I | 5 | Destinazioni per ambiente |  |  |
| I | 5 | JCo installato in Tomcat |  |  |
| I | 5 | Trasporto degli oggetti ABAP |  |  |
| I | 5 | Attivazione per tipo documento |  |  |
| I | 6 | Migrazione della configurazione |  |  |
| I | 6 | Utenti e password |  |  |
| I | 6 | Processi in corso al passaggio |  |  |
| I | 6 | Piano di ritorno |  |  |
| I | 7 | Versioni di rilascio |  |  |
| I | 7 | Pom aggregatore |  |  |
| I | 7 | Deploy senza il designer |  |  |
| I | 7 | Configurazione esterna |  |  |
| I | 7 | Log su file |  |  |
| I | 8 | Job dentro l'applicazione |  | Fatto nel prototipo |
| I | 8 | Gestione dei processi in errore |  | Fatto nel prototipo |
| I | 8 | Gestione utenti e password |  | Fatto nel prototipo |
| I | 8 | Link diretto nelle mail |  | Fatto nel prototipo |
| I | 9 | Segreti nel servizio |  |  |
| I | 9 | HTTPS e intestazioni di sicurezza |  |  |
| I | 9 | Account di amministrazione |  |  |
| I | 10 | Scelta VPN o pubblicazione |  |  |
| I | 10 | MFA prima di pubblicare |  |  |
| I | 10 | Reverse proxy e certificato pubblico |  |  |
| I | 10 | Indirizzo pubblico nelle mail |  |  |
| I | 10 | Funzioni di amministrazione |  |  |
| I | 11 | Piano di prova funzionale |  |  |
| I | 11 | Collaudo con il cliente |  |  |
| I | 12 | Controllo quotidiano |  |  |
| I | 12 | Avvisi automatici |  |  |
| I | 12 | Procedura dei guasti |  |  |
| C | 3 | Server dimensionato |  |  |
| C | 3 | Orario e fuso |  |  |
| C | 4 | Manutenzione |  |  |
| C | 4 | Conservazione dei dati |  |  |
| C | 5 | Raggiungibilità di rete |  |  |
| C | 5 | Controllo dei blocchi |  |  |
| C | 6 | Comunicazione agli utenti |  |  |
| C | 7 | Pagina di stato |  |  |
| C | 7 | Numero di versione visibile |  |  |
| C | 8 | Presa in carico dei task di team |  |  |
| C | 8 | Gestione dei sostituti dall'applicazione |  | Fatto nel prototipo |
| C | 8 | Apri in SAP |  | Fatto nel prototipo |
| C | 9 | Accesso a due fattori (MFA) |  |  |
| C | 9 | Sessione singola |  |  |
| C | 9 | Dati personali (GDPR) |  |  |
| C | 9 | Aggiornamenti |  |  |
| C | 10 | Sessioni sul telefono |  |  |
| C | 10 | Protezione dai tentativi di accesso |  |  |
| C | 10 | Allegati scaricati sul telefono |  |  |
| C | 10 | Registro degli accessi |  |  |
| C | 11 | Prova di carico leggera |  |  |
| C | 11 | Prova di guasto |  |  |
| C | 12 | Accordo di assistenza |  |  |
| P | 3 | Più istanze di Tomcat |  |  |
| P | 4 | Catena di hash dell'audit |  |  |
| P | 7 | Icona dell'applicazione |  |  |
| P | 8 | Allegati GOS |  | Fatto nel prototipo |
| P | 8 | Controllo di budget |  |  |
| P | 10 | Prova di sicurezza |  |  |
