# eWF – Processi vostri, core pulito

Nov 23, 2026 · @Giuseppe

eWF porta fuori dal nucleo ERP le regole di approvazione e i processi aziendali, in un posto che appartiene al cliente: **il core resta pulito, i processi restano vostri**. Lo stesso motore lavora con SAP on-premise, S/4HANA Public Cloud, altri gestionali aperti alle integrazioni o nessun gestionale, e da web o da telefono.

Uso previsto: argomenti per commerciali e consulenti e-One, e base per una presentazione a un cliente. Il messaggio non è "contro SAP": è la stessa direzione che SAP indica con il clean core, ma senza legare il cliente a un'unica piattaforma per le estensioni.

## Il contesto: perché adesso

Oggi un'azienda che vuole processi su misura si trova davanti a due strade che vincolano entrambe.

- **Personalizzare il nucleo ERP** (Z, exit, BAdI): veloce all'inizio, poi ogni aggiornamento costa. Su S/4HANA Public Cloud non è nemmeno possibile.
- **Estendere sulla piattaforma del fornitore** (BTP, workflow e servizi cloud dedicati): pulito, ma il cliente paga a consumo, dipende da un unico fornitore e da un'unica roadmap.

Il clean core di SAP dice una cosa giusta: il nucleo standard non si tocca, le personalizzazioni stanno fuori. eWF è "fuori" senza imporre dove: gira su un server del cliente o in un cloud a sua scelta, con tecnologie comuni.

Il legacy `/EWF/` (ABAP) ha dimostrato per anni il valore di un workflow di approvazione configurabile. eWF ne conserva la logica e la rende indipendente dal sistema SAP e dalla sua versione.

## Cosa offre eWF

Un solo motore di processo, indipendente dal gestionale; il collegamento a ciascun sistema è un modulo separato e sostituibile.

- **Un motore, molti sistemi di origine.** Il motore parla solo con interfacce delimitate (leggere il documento, sapere quando cambia, scrivere l'esito, allegati). Per SAP on-premise e Private c'è il modulo via RFC; per S/4HANA Public Cloud quello via OData è in preparazione. Un altro gestionale con API aperte richiede un modulo dello stesso tipo, non un nuovo prodotto.
- **Processi anche senza gestionale.** Ferie, richieste interne, acquisti fuori ERP: il documento nasce in eWF stesso (modulo "Nuova richiesta", in programma).
- **Configurato, non programmato.** Processi, step, regole di assegnazione e di avvio si disegnano in un designer con simulatori ("chi lo riceve?", "quale processo parte?"); le versioni attive sono protette anche a livello di database.
- **Ovunque.** Web per il lavoro d'ufficio, versione mobile per approvare dal telefono, avvisi via mail con link diretto al task.
- **Tracciabile.** Ogni passo, commento e decisione è registrato; il commento può essere reso obbligatorio task per task.
- **Un cliente, più sistemi.** Lo stesso eWF può lavorare insieme con un ECC e una Public Cloud, ciascuno con i propri processi.

&#91;embedded content: architettura · canali, motore, collegamenti, sistemi di origine\]

Cambiare o aggiungere un sistema di origine tocca solo la fila dei collegamenti: canali e motore restano gli stessi.

## La libertà, resa credibile

Promettere libertà funziona solo se il cliente vede che non sta cambiando padrone: ogni punto qui sotto deve essere verificabile.

| Ambito | Cosa garantiamo | Stato |
| --- | --- | --- |
| Dati | Tutto in PostgreSQL, schema documentato e versionato con migrazioni numerate; la storia delle approvazioni resta del cliente e leggibile anche senza eWF | Fatto |
| Configurazione | Processi, regole e ruoli in tabelle documentate; export/import delle definizioni per passare da test a produzione o da un cliente a un altro | Da fare |
| Tecnologie | Java, Tomcat, PostgreSQL, OData e RFC: competenze diffuse, nessun runtime esotico | Fatto |
| Interfaccia utente | Unico componente proprietario (CaptainCasa), limitato alle pagine: motore e servizi ne sono indipendenti, come dimostra la versione mobile scritta senza toccarli | Da dichiarare apertamente |
| Estensioni | Interfacce (SPI) documentate: anche uno sviluppatore del cliente può scrivere un adapter o un handler | Da documentare per terzi |
| Garanzie | Licenza chiara; deposito del codice sorgente (escrow) per i clienti che lo chiedono | Da definire |
| Sicurezza | Password mai nei file, permessi minimi sul database, protezione delle versioni attive; HTTPS e MFA prima di ogni accesso da fuori rete | In parte fatto |

## Obiezioni prevedibili

| Obiezione | Risposta |
| --- | --- |
| "Esco dal vincolo SAP per entrare in quello di e-One." | I dati e la configurazione restano del cliente in formato aperto; tecnologie standard; escrow del codice su richiesta. Il vincolo che resta è un contratto di servizio, non una piattaforma. |
| "SAP ha già il flexible workflow." | Vale dentro un solo sistema SAP e con le sue regole. eWF governa processi che attraversano sistemi diversi, o che SAP non conosce, con regole configurabili dal cliente. |
| "Perché non SAP Build Process Automation su BTP?" | È una scelta valida se il cliente è già tutto su BTP. eWF non richiede BTP né costi a consumo, e gira anche on-premise. |
| "Su S/4 Public non si può approvare da fuori." | Vero: non c'è un'API standard. Stiamo verificando una soluzione con soli strumenti ammessi (campo custom e riavvio del workflow); il risultato sarà documentato prima di proporlo. |
| "Un'azienda piccola: chi garantisce la continuità?" | Codice in deposito, documentazione di progetto e registro delle decisioni, tecnologie che altri sviluppatori conoscono. |
| "E la sicurezza se lo apriamo da fuori?" | Nessuna pubblicazione senza HTTPS e MFA; in alternativa accesso via VPN aziendale. Le funzioni di amministrazione restano interne. |
| "L'interfaccia è solo in italiano?" | Oggi sì; l'internazionalizzazione è nel piano per renderlo esportabile. |

## Cosa è dimostrato e cosa manca

Oggi funziona il caso completo su SAP on-premise; le prove che trasformano "si può" in "è dimostrato" sono quattro, e la seconda è la prossima.

1. **Un backend in esercizio di prova (fatto).** Ordine d'acquisto da SAP via RFC: avvio dalla modifica in ME22N, approvazione web e mobile, allegati SAP, rilascio finale, gestione degli errori.
2. **Un secondo backend di natura diversa (in corso).** S/4HANA Public Cloud via OData, con avvio per polling. Prima uno spike di 1–2 giorni sul rilascio dell'OdA, il punto più incerto.
3. **Un processo senza gestionale (in programma).** Il modulo "Nuova richiesta": dimostra che eWF vale per ogni area aziendale.
4. **Un verticale completo (obiettivo).** eWF come pezzo trasversale di un pacchetto di moduli di settore, accanto al core ERP.

Per essere esportabile, oltre le prove serve il lavoro da prodotto:

- installazione ripetibile e guidata;
- interfaccia in più lingue;
- export/import delle configurazioni;
- documentazione per l'utente finale e per chi scrive adapter;
- aggiornamenti che non rompono le configurazioni dei clienti (già impostato con le migrazioni numerate);
- un modello di assistenza e di licenza.

## Il valore per e-One

Il valore non è solo il software: è un metodo che permette a un piccolo gruppo di consulenti esperti di replicare una soluzione da un cliente all'altro senza ripartire da zero.

- **Verticali come insieme di moduli.** Un core ERP standard, moduli di settore e un pezzo trasversale (eWF) per approvazioni, eccezioni e tracciabilità: è la base per un'offerta di consulenza riconoscibile, per esempio nel settore ceramico.
- **Un metodo ripetibile.** Interfacce delimitate, spike sul punto incerto prima di costruire, decisioni registrate con il loro perché, schema che evolve per migrazioni. È questo che rende ogni nuovo cliente o backend un lavoro prevedibile.
- **Competenza SAP come vantaggio, non come vincolo.** Trent'anni di esperienza funzionale e tecnica servono a capire i processi e i sistemi di origine; la soluzione resta indipendente da un singolo fornitore.

Prossimo passo concreto: lo spike su S/4HANA Public Cloud, poi il secondo processo disegnato da zero nel designer.
