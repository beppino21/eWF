FUNCTION /ewf/start_q_process.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:
*"  IMPORTING
*"     VALUE(I_MAX)          TYPE  INT4 DEFAULT 200
*"     VALUE(I_MAX_ATTEMPTS) TYPE  INT4 DEFAULT 20
*"  EXPORTING
*"     VALUE(E_DONE)   TYPE  INT4
*"     VALUE(E_ERRORS) TYPE  INT4
*"     VALUE(E_BUSY)   TYPE  CHAR1
*"----------------------------------------------------------------------
* Elabora le righe legacy (ENGINE = L) della coda degli avvii, in ordine
* di nascita, una alla volta con /EWF/START_Q_PROCESS_ONE.
*
* - Un solo elaboratore alla volta nel sistema (lock su /EWF/START_Q):
*   se un altro sta girando, esce subito con E_BUSY = X.
* - Rilegge la coda finche' trova righe nuove: gli eventi arrivati mentre
*   girava vengono presi nello stesso giro.
* - Ogni riga viene tentata al massimo una volta per giro.
* - Ordine per documento: se un evento va in errore, i successivi dello
*   stesso documento restano in coda (verranno ripresi dopo di lui).
*----------------------------------------------------------------------
  TYPES: BEGIN OF ty_row,
           qid      TYPE /ewf/start_q-qid,
           tipodoc  TYPE /ewf/start_q-tipodoc,
           document TYPE /ewf/start_q-document,
         END OF ty_row.
  DATA: lt_rows     TYPE STANDARD TABLE OF ty_row,
        ls_row      TYPE ty_row,
        lt_seen     TYPE SORTED TABLE OF /ewf/start_q-qid WITH UNIQUE KEY table_line,
        lt_blocked  TYPE SORTED TABLE OF string WITH UNIQUE KEY table_line,
        lv_key      TYPE string,
        lv_varkey   TYPE rstable-varkey,
        lv_progress TYPE i,
        lv_status   TYPE char1,
        lv_message  TYPE char255.

  CLEAR: e_done, e_errors, e_busy.

* Lock logico "elaboratore legacy": _SCOPE = 1, resta nostro anche dopo
* i COMMIT WORK fatti riga per riga, fino al DEQUEUE.
  CONCATENATE sy-mandt 'EWF_LEGACY_PROCESSOR' INTO lv_varkey.
  CALL FUNCTION 'ENQUEUE_E_TABLE'
    EXPORTING
      mode_rstable   = 'E'
      tabname        = '/EWF/START_Q'
      varkey         = lv_varkey
      _scope         = '1'
      _wait          = ' '
    EXCEPTIONS
      foreign_lock   = 1
      system_failure = 2
      OTHERS         = 3.
  IF sy-subrc <> 0.
    e_busy = 'X'.
    RETURN.
  ENDIF.

  DO 50 TIMES.
    SELECT qid tipodoc document FROM /ewf/start_q
      INTO TABLE lt_rows
      UP TO i_max ROWS
      WHERE engine = 'L'
        AND ( status = space OR status = 'E' )
        AND attempts < i_max_attempts
      ORDER BY created_at qid.

    lv_progress = 0.
    LOOP AT lt_rows INTO ls_row.
      READ TABLE lt_seen WITH TABLE KEY table_line = ls_row-qid
        TRANSPORTING NO FIELDS.
      CHECK sy-subrc <> 0.
      INSERT ls_row-qid INTO TABLE lt_seen.

      CONCATENATE ls_row-tipodoc '|' ls_row-document INTO lv_key.
      READ TABLE lt_blocked WITH TABLE KEY table_line = lv_key
        TRANSPORTING NO FIELDS.
      IF sy-subrc = 0.
*       Dietro a un errore dello stesso documento: aspetta il prossimo giro.
        CONTINUE.
      ENDIF.

      CALL FUNCTION '/EWF/START_Q_PROCESS_ONE'
        EXPORTING
          i_qid     = ls_row-qid
        IMPORTING
          e_status  = lv_status
          e_message = lv_message.
      lv_progress = lv_progress + 1.
      IF lv_status = 'E'.
        e_errors = e_errors + 1.
        INSERT lv_key INTO TABLE lt_blocked.
      ELSE.
        e_done = e_done + 1.
      ENDIF.
    ENDLOOP.

    IF lv_progress = 0.
      EXIT.
    ENDIF.
  ENDDO.

  CALL FUNCTION 'DEQUEUE_E_TABLE'
    EXPORTING
      mode_rstable = 'E'
      tabname      = '/EWF/START_Q'
      varkey       = lv_varkey
      _scope       = '1'.

ENDFUNCTION.
