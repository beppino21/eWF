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

## Build

eone-connect → ewf-engine → ewf-service → ewf-sap → ewfserver (`mvn install`, poi deploy dal designer).

## Non ancora fatto

MFA e sessione unica (come nel ticketing), registro accessi, link nelle mail di avviso (`EwfApp.taskLink`),
gestione utenti e reset password dall'app (oggi `PasswordTool`).

## Struttura

Progetto creato dal designer CaptainCasa (nome = contesto web = WAR: `ewfserver`).
Package: `it.eone.ewf.ewfserver.view` (pagine, unico package in hot deployment),
`.app` (avvio servizi), `.config`, `.context` (sessione), `.web` (filtro HTTP).
