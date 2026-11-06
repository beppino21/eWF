# eWF SAP connector

Collega il motore eWF a SAP: ogni handler ABAP viene eseguito tramite la FM RFC
`/EWF/ENGINE_DISPATCH` (vedi `ewf_handler_contract.md`).

| Classe | Ruolo |
|---|---|
| `JcoDispatcherHandler` | Handler del motore che chiama `/EWF/ENGINE_DISPATCH` per una FM |
| `SapHandlerRegistry` | Fornisce un `JcoDispatcherHandler` per i codici che sono FM ABAP (`/EWF/...`, `Z...`); `chain(...)` combina più registri |
| `SapDestinations` | Destinazioni JCo da file esterni, password da variabile d'ambiente |
| `DispatcherProbe` | Prova a riga di comando: esegue un handler due volte con la stessa chiave |

## Configurazione della destinazione (niente password nel codice)

1. Crea una cartella **fuori** dal progetto e da Git, es. `C:\ewf-config`.
2. Copia `config/EWF_DEV.properties.example` in `C:\ewf-config\EWF_DEV.properties`
   e compila host, istanza, mandante, utente RFC.
3. Variabili d'ambiente:
   - `EWF_SAP_CONFIG_DIR=C:\ewf-config`
   - `EWF_SAP_EWF_DEV_PASSWD=<password dell'utente RFC>`

Se nel file `.properties` c'è una password, il connettore si rifiuta di partire.

## Prova da Eclipse (Windows)

1. Sul progetto `ewf-engine`: *Run As → Maven install* (pubblica il motore nel repository
   locale, così `ewf-sap` lo trova).
2. Importa `ewf-sap` (*Existing Maven Projects*), poi Alt+F5.
3. Clic destro su `DispatcherProbe.java` → *Run As → Run Configurations…* → *Java Application* → nuova:
   - **Arguments → Program arguments**: `EWF_DEV /EWF/WF_DUMMY 4500000001`
   - **Arguments → VM arguments**: `-Djava.library.path=C:\percorso\cartella\di\sapjco3.dll`
   - **Environment**: le due variabili sopra.
4. *Run*. Output atteso:

```
--- 1a chiamata
esito:      OK
--- 2a chiamata (stessa chiave)
esito:      OK  (dal registro, non rieseguita)
```

Se JCo segnala *"Illegal JCo archive"* (il jar nel repository Maven è rinominato in
`sapjco3-3.1.12.jar` e JCo 3.1 accetta solo il nome originale): in *Run Configurations →
Dependencies → Classpath Entries → Add External JARs* aggiungi il `sapjco3.jar` originale
e portalo in cima con *Up*.

Altre prove utili:

| Argomenti | Atteso |
|---|---|
| `EWF_DEV /EWF/WF_CHECK_LOCK_EKKO <OdA>` | KO finché la FM rilegge TASKRUN dal DB; OK dopo il ritocco |
| idem, con l'OdA aperto in modifica in ME22N | KO "Ordine di Acquisto in uso" |
| `EWF_DEV /EWF/NON_ESISTE 1` | KO "Handler inesistente" |
| `EWF_DEV /EWF/WF_RIL_ODA <OdA> F Z_WF_SET_RIL=01` | il codice di rilascio arriva in `I_TASKRUN-Z_WF_SET_RIL` |

## Parametri degli agganci

Nella `config` di un aggancio (`task_hook.config`):
- chiave = nome di un campo di `/EWF/TASKRUN` (es. `Z_WF_SET_RIL`) → valorizza quel campo
  di `I_TASKRUN`: le FM legacy lo leggono come oggi;
- altre chiavi → `IT_PARAMS`: il dispatcher le passa al parametro omonimo della FM
  (es. `I_AUART`), convertite nel tipo dichiarato.

## Esiti

| Situazione | Esito per il motore |
|---|---|
| SAP non raggiungibile / dispatcher assente (prima della chiamata) | KO (si può riprovare) |
| Errore durante la chiamata (rete, dump) | UNKNOWN (va verificato) |
| `E_STATUS` O / K / U | OK / KO / UNKNOWN |
