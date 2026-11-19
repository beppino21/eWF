# ewfserver

Applicazione web di eWF (CaptainCasa, Jakarta, Tomcat 11). Indirizzo: `https://<server>/ewfserver/Outest.risc` (in sviluppo `http://localhost:50000/ewfserver/Outest.risc`).

Impostazione ripresa dal ticketing:
- **Outest** è l'unica pagina avviabile (`system.xml`, `riscstarter`); logon, lista e dettaglio sono inclusi
  dentro Outest (`rowpagebeaninclude`). Ogni pagina protetta chiama `SessionGuard` nel costruttore.
- Collegamento diretto a un task (per le mail): `.../Outest.risc?task=<id task>`, aperto dopo il logon.
- `SecurityHeadersFilter`, CSP restrittiva ed `ErrorAnonymizerFilter` come nel ticketing.

Pagine: `Logon` (con cambio password provvisoria), `Worklist` (semaforo, filtro per tipo, ricerca, colonne del tipo),
`TaskDetail` (azioni, schede PDF, SAP GUI, percorso, storico, commenti, allegati).
La logica è tutta in `ewf-service`; qui solo interfaccia.

## Configurazione

File esterno al WAR, come nel ticketing (vedi `config.properties.esempio.txt`): `C:\eone\ewf\config.properties`
o `-Dewf.config.path=...`. **Le password solo da variabili d'ambiente** di Tomcat:
`EWF_DB_PASSWORD`, `EWF_SAP_<DEST>_PASSWD`; la cartella JCo con `EWF_SAP_CONFIG_DIR`.
Senza `EWF_SAP_DEST` (o senza JCo in Tomcat) l'applicazione parte senza SAP.

JCo è `provided`: `sapjco3.jar` (nome originale) e la libreria nativa nella `lib` di Tomcat.

## Job e pool di connessioni

All'avvio di Tomcat `JobHost` (registrato in `web.xml`) crea i servizi e avvia lo scheduler di
eone-connect con i job di eWF: coda degli avvii da SAP (ogni 30 s, solo se SAP e' collegato), avvisi
immediati e invio mail (ogni minuto), riepilogo (giorni feriali 07:15). Alla chiusura li ferma e chiude
il pool di connessioni. Il risultato e' scritto nella console di Tomcat (`[JobHost] ...`).

- I job si creano in `eone_connect.job` solo se mancano; pianificazione e attivazione si cambiano li'.
- Con piu' Tomcat sullo stesso database ogni job gira su uno solo; `EWF_JOBS_ENABLED=false` li spegne su un'istanza.
- Senza `EONE_SMTP_HOST` le mail restano in coda; senza `EWF_APP_BASE_URL` le mail non hanno il link al task.
- Il link nelle mail e' `<EWF_APP_BASE_URL>/Outest.risc?task=<id>`.
- Connessioni al database: pool HikariCP (`EWF_DB_POOL_SIZE`, default 15).

- "Aggiorna" nella lista chiede allo scheduler di leggere subito la coda degli avvii (lo scheduler gira
  ogni 2 s, quindi l'attesa e' di 2-3 secondi al massimo) e poi rilegge la lista.

## Menu e funzioni

Dopo l'accesso, barra in alto: **Le mie attivita'**, **Sostituzioni**, **Processi in errore** (solo
amministratori), **Cambio password**, **Esci**.

- Amministratore = membro attivo del team di riserva del motore (`WFADM`).
- Sostituzioni: ognuno vede chi lo sostituisce e chi sta sostituendo; le cambia solo chi ha
  `can_substitute` (sull'utente o, se vuoto, sulla sua classe utente). Una sostituzione in corso si
  termina (resta nello storico), una futura si cancella.
- Processi in errore: esito incerto di una chiamata a SAP (si verifica in SAP e si conferma, nota
  obbligatoria) e azione di fine processo non riuscita (si riprova).

Permessi del database: `ewf-engine/db/ewf_grants_app.sql`, da eseguire come `ewf_owner` dopo ogni
installazione o migrazione (rieseguibile).

## Build

eone-connect → ewf-engine → ewf-service → ewf-sap → ewfserver (`mvn install`, poi deploy dal designer).

## Non ancora fatto

MFA e sessione unica (come nel ticketing), registro accessi, gestione utenti e reset password
dall'app (oggi `PasswordTool`), stato dei job.

## Struttura

Progetto creato dal designer CaptainCasa (nome = contesto web = WAR: `ewfserver`).
Package: `it.eone.ewf.ewfserver.view` (pagine, unico package in hot deployment),
`.app` (avvio servizi), `.config`, `.context` (sessione), `.web` (filtro HTTP).
