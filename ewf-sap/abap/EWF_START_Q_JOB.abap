*&---------------------------------------------------------------------*
*& Report /EWF/START_Q_JOB
*&---------------------------------------------------------------------*
*& Elabora le righe legacy della coda degli avvii eWF.
*& - job immediato (pianificato da /EWF/START_Q_KICK, modalita' I);
*& - job periodico in SM36 (es. ogni 5 minuti): errori da ritentare e
*&   tutto quello che e' rimasto indietro.
*& Si puo' anche lanciare a mano da SE38 per una prova.
*&---------------------------------------------------------------------*
REPORT /ewf/start_q_job.

PARAMETERS: p_max TYPE int4 DEFAULT 200,
            p_att TYPE int4 DEFAULT 20.

DATA: gv_done   TYPE int4,
      gv_errors TYPE int4,
      gv_busy   TYPE char1.

START-OF-SELECTION.
  CALL FUNCTION '/EWF/START_Q_PROCESS'
    EXPORTING
      i_max          = p_max
      i_max_attempts = p_att
    IMPORTING
      e_done         = gv_done
      e_errors       = gv_errors
      e_busy         = gv_busy.

  IF gv_busy = 'X'.
    WRITE: / 'Elaboratore gia'' in esecuzione: nessuna azione.'.
  ELSE.
    WRITE: / 'Righe elaborate:', gv_done,
           / 'Righe in errore:', gv_errors.
  ENDIF.
