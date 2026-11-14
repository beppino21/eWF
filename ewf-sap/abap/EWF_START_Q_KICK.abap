FUNCTION /ewf/start_q_kick.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (FM ABILITATA RFC - chiamata come tRFC)
*"----------------------------------------------------------------------
* "Sveglia" dell'elaboratore legacy: pianifica subito un job che esegue
* /EWF/START_Q_JOB. Registrata da /EWF/START_Q_WRITE IN BACKGROUND TASK:
* SAP la esegue solo dopo che il documento e' stato salvato.
*
* Non elabora la coda qui dentro: in un tRFC non si possono fare COMMIT
* intermedi, e l'elaborazione ne fa uno per riga. Il job invece si',
* compare in SM37 e non tiene occupato un processo di dialogo.
*
* Se un elaboratore sta gia' girando, il nuovo job esce subito (lock in
* /EWF/START_Q_PROCESS): quello in corso rilegge la coda prima di finire.
*----------------------------------------------------------------------
  DATA: lv_jobname  TYPE tbtcjob-jobname VALUE 'EWF_START_Q',
        lv_jobcount TYPE tbtcjob-jobcount.

  CALL FUNCTION 'JOB_OPEN'
    EXPORTING
      jobname          = lv_jobname
    IMPORTING
      jobcount         = lv_jobcount
    EXCEPTIONS
      cant_create_job  = 1
      invalid_job_data = 2
      jobname_missing  = 3
      OTHERS           = 4.
  IF sy-subrc <> 0.
*   Nessun problema: ci pensa il job periodico.
    RETURN.
  ENDIF.

  SUBMIT /ewf/start_q_job
    VIA JOB lv_jobname NUMBER lv_jobcount
    AND RETURN.

  CALL FUNCTION 'JOB_CLOSE'
    EXPORTING
      jobcount             = lv_jobcount
      jobname              = lv_jobname
      strtimmed            = 'X'
    EXCEPTIONS
      cant_start_immediate = 1
      invalid_startdate    = 2
      jobname_missing      = 3
      job_close_failed     = 4
      job_nosteps          = 5
      job_notex            = 6
      lock_failed          = 7
      OTHERS               = 8.
* Errori ignorati di proposito: il job periodico recupera le righe.

ENDFUNCTION.
