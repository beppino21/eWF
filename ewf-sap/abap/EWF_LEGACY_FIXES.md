# Correzioni al legacy /EWF/ (standard)

## 1. `/EWF/WF_RESTART_PROCESS` — chiusura dei task aperti di tutti i processi

`AND` lega più forte di `OR`: la condizione attuale chiude ogni task aperto del sistema.

```abap
*   prima
    UPDATE /ewf/taskrun SET
      z_dt_stop = sy-datum
      z_hh_stop = sy-uzeit
      z_wf_azione  = 'RESTART'
       WHERE z_wf_procid EQ i_process-z_wf_procid
        AND z_dt_stop EQ space OR z_dt_stop EQ '00000000'.

*   dopo
    UPDATE /ewf/taskrun SET
      z_dt_stop = sy-datum
      z_hh_stop = sy-uzeit
      z_wf_azione  = 'RESTART'
       WHERE z_wf_procid EQ i_process-z_wf_procid
        AND ( z_dt_stop EQ space OR z_dt_stop EQ '00000000' ).
```

**È chiamata dal metodo CLOSE della BAdI `ME_PROCESS_PO_CUST`** (OdA modificato con processo
oltre il primo step e valore aumentato o INCO1/LIFNR/WAERS/ZTERM cambiati).
Ovunque quella BAdI sia attiva, la correzione è urgente.

Controllo dei danni passati (SE16 / SQL) in quei sistemi: task con `Z_WF_AZIONE = 'RESTART'`
appartenenti a processi diversi da quello dell'OdA modificato, chiusi nello stesso istante
(stessi `Z_DT_STOP` / `Z_HH_STOP`).

## 2. `/EWF/WF_START` — pulizia dopo "utente non determinabile"

Confronta l'ID processo con il codice del processo: non cancella niente e lascia
`/EWF/PROCDOC`, `/EWF/PROCRUN`, `/EWF/TASKDOC` di un processo senza task.

```abap
*   prima
      DELETE FROM /ewf/taskrun  WHERE z_wf_procid = i_processo.
      DELETE FROM /ewf/procdoc  WHERE z_wf_procid = i_processo.
      DELETE FROM /ewf/procrun  WHERE z_wf_procid = i_processo.
      DELETE FROM /ewf/taskdoc  WHERE z_wf_procid = i_processo.

*   dopo
      DELETE FROM /ewf/taskrun  WHERE z_wf_procid = wk_procid.
      DELETE FROM /ewf/procdoc  WHERE z_wf_procid = wk_procid.
      DELETE FROM /ewf/procrun  WHERE z_wf_procid = wk_procid.
      DELETE FROM /ewf/taskdoc  WHERE z_wf_procid = wk_procid.
```

Stessa correzione in `/EWF/WF_START_ARCO`, se è una copia di `/EWF/WF_START`.

Con la coda il problema non si presenta comunque: in errore l'elaboratore fa `ROLLBACK WORK`
e non resta niente a metà. La correzione serve per le chiamate dirette che restano.

## 3. Da sapere (non da correggere ora)

`/EWF/WF_START` chiama `POPUP_TO_INFORM` quando il processo è già avviato.
In background, cioè nel job della coda, un popup manda in dump il programma (`DYNPRO_SEND_IN_BACKGROUND`).
L'elaboratore controlla prima se c'è un processo aperto, quindi quel ramo non viene raggiunto.
Se però la stessa chiamata arrivasse da un'altra strada in background, il problema resta.
