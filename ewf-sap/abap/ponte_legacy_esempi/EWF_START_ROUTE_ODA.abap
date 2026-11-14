FUNCTION /ewf/start_route_oda.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (interfaccia fissa degli instradatori)
*"  IMPORTING
*"     VALUE(I_QUEUE) TYPE  /EWF/START_Q
*"  EXPORTING
*"     VALUE(E_ACTION)   TYPE  CHAR1
*"     VALUE(E_TARGET)   TYPE  CHAR10
*"     VALUE(E_PROCESSO) TYPE  /EWF/WF_PROCESSO
*"     VALUE(E_DOCUMENT) TYPE  /EWF/WF_DOCUMENT
*"     VALUE(E_NOTE)     TYPE  CHAR70
*"     VALUE(E_ERROR)    TYPE  CHAR1
*"     VALUE(E_RETURN)   TYPE  CHAR80
*"----------------------------------------------------------------------
* Instradatore OdA (processo EONE_ODA, tipo documento F).
* E' la logica dei metodi POST + CLOSE di ME_PROCESS_PO_CUST, eseguita
* dopo il salvataggio e leggendo l'OdA dal DB:
*
*  condizioni     processo EONE_ODA attivo, utente iscritto al WF,
*                 tipo OdA ZNB
*  OdA nuovo      (IS_NEW = X) rilascio automatico se WF_AUTO_REL_ODA,
*                 altrimenti avvio se valore >= limite              -> A
*  processo       oltre il primo step e modifica rilevante          -> R
*  in corso
*  processo       modifica rilevante: nuovo processo                -> A
*  concluso
*  altrimenti     nessuna azione                                    -> N
*
*  modifica rilevante = valore aumentato rispetto al processo, oppure
*  cambio di INCO1 / LIFNR / WAERS / ZTERM in testata nei documenti di
*  modifica dell'utente intorno al momento del salvataggio.
*
* Destinazione ODA: /EWF/WF_START_ODA, /EWF/WF_RESTART_ODA.
* Il valore calcolato viene passato al riavvio in memoria
* (ID EWF_ODA_NETWR) per aggiornare /EWF/PROCDOC come prima.
*----------------------------------------------------------------------
  CONSTANTS: lc_processo TYPE /ewf/wf_processo VALUE 'EONE_ODA',
             lc_tipodoc  TYPE /ewf/wf_tipodoc  VALUE 'F',
             lc_bsart    TYPE esart            VALUE 'ZNB',
             lc_limit    TYPE netwr            VALUE 0,
             lc_window   TYPE i                VALUE 120.   " secondi

  TYPES: BEGIN OF ty_cdhdr,
           changenr TYPE cdhdr-changenr,
           udate    TYPE cdhdr-udate,
           utime    TYPE cdhdr-utime,
         END OF ty_cdhdr,
         BEGIN OF ty_cdpos,
           fname     TYPE cdpos-fname,
           value_new TYPE cdpos-value_new,
           value_old TYPE cdpos-value_old,
         END OF ty_cdpos.

  DATA: ls_processo  TYPE /ewf/processo,
        ls_user0     TYPE /ewf/user0,
        ls_ekko      TYPE ekko,
        ls_procrun   TYPE /ewf/procrun,
        ls_procrunhst TYPE /ewf/procrunhst,
        lv_ebeln     TYPE ebeln,
        lv_mode      TYPE char1,          " N nuovo, R in corso, H concluso
        lv_waers     TYPE waers,
        lv_netwr     TYPE netwr,
        lv_netwr_old TYPE netwr,
        lv_waers_c   TYPE tcurc-waers,
        lv_impo      TYPE bapicurr-bapicurr,
        lv_auto_rel  TYPE /ewf/parametri-parvaluex,
        lv_first     TYPE /ewf/wf_step,
        lv_current   TYPE /ewf/wf_step,
        lv_relevant  TYPE abap_bool,
        lt_return    TYPE STANDARD TABLE OF bapireturn,
        lv_rel_code  TYPE bapimmpara-po_rel_cod VALUE '01',
        lv_systz     TYPE timezone,
        lv_event_ts  TYPE timestamp,
        lv_from      TYPE timestamp,
        lv_to        TYPE timestamp,
        lv_ts        TYPE timestamp,
        lv_date      TYPE d,
        lv_objid     TYPE cdhdr-objectid,
        lt_cdhdr     TYPE STANDARD TABLE OF ty_cdhdr,
        ls_cdhdr     TYPE ty_cdhdr,
        lt_cdpos     TYPE STANDARD TABLE OF ty_cdpos,
        ls_cdpos     TYPE ty_cdpos.

  CLEAR: e_action, e_target, e_processo, e_document, e_note,
         e_error, e_return.
  e_document = i_queue-document.
  e_processo = lc_processo.
  e_target   = 'ODA'.
  lv_ebeln   = i_queue-document.

*--- condizioni ------------------------------------------------------------
  SELECT SINGLE * FROM /ewf/processo INTO ls_processo
    WHERE z_wf_processo = lc_processo.
  IF sy-subrc <> 0 OR ls_processo-z_wf_sospeso <> space.
    e_action = 'N'.
    e_return = 'Processo EONE_ODA non attivo'.
    RETURN.
  ENDIF.

  SELECT SINGLE * FROM /ewf/user0 INTO ls_user0
    WHERE uname = i_queue-uname AND annullato = space.
  IF sy-subrc <> 0.
    e_action = 'N'.
    CONCATENATE 'Utente' i_queue-uname 'non iscritto al WF'
      INTO e_return SEPARATED BY space.
    RETURN.
  ENDIF.

  SELECT SINGLE * FROM ekko INTO ls_ekko WHERE ebeln = lv_ebeln.
  IF sy-subrc <> 0.
    e_error = 'X'.                              " si ritenta
    CONCATENATE 'OdA' lv_ebeln 'non trovato' INTO e_return SEPARATED BY space.
    RETURN.
  ENDIF.

  IF ls_ekko-bsart <> lc_bsart.
    e_action = 'N'.
    CONCATENATE 'Tipo OdA' ls_ekko-bsart 'escluso dal WF' INTO e_return SEPARATED BY space.
    RETURN.
  ENDIF.

*--- valore (come nel metodo CLOSE) -------------------------------------------
  CALL FUNCTION '/EWF/WF_VALORE_DOCUMENTO'
    EXPORTING
      i_document = e_document
      i_tipodoc  = lc_tipodoc
    IMPORTING
      e_waers    = lv_waers
      e_netwr    = lv_netwr.
  lv_waers_c = lv_waers.
  lv_impo    = lv_netwr.
  CALL FUNCTION 'BAPI_CURRENCY_CONV_TO_INTERNAL'
    EXPORTING
      currency             = lv_waers_c
      amount_external      = lv_impo
      max_number_of_digits = 18
    IMPORTING
      amount_internal      = lv_impo
    EXCEPTIONS
      OTHERS               = 1.
  lv_netwr = lv_impo.
  EXPORT netwr = lv_netwr TO MEMORY ID 'EWF_ODA_NETWR'.

*--- situazione del documento ---------------------------------------------------
  SELECT SINGLE * FROM /ewf/procrun INTO ls_procrun
    WHERE z_wf_tipodoc = lc_tipodoc AND z_wf_document = e_document.
  IF sy-subrc = 0.
    lv_mode = 'R'.
    SELECT MAX( z_wf_netwr ) FROM /ewf/procdoc INTO lv_netwr_old
      WHERE z_wf_tipodoc = lc_tipodoc AND z_wf_document = e_document.
  ELSE.
    SELECT SINGLE * FROM /ewf/procrunhst INTO ls_procrunhst
      WHERE z_wf_tipodoc = lc_tipodoc AND z_wf_document = e_document.
    IF sy-subrc = 0.
      lv_mode = 'H'.
      SELECT MAX( z_wf_netwr ) FROM /ewf/procdochst INTO lv_netwr_old
        WHERE z_wf_tipodoc = lc_tipodoc AND z_wf_document = e_document.
    ELSE.
      lv_mode = 'N'.
    ENDIF.
  ENDIF.

*--- OdA nuovo ---------------------------------------------------------------------
  IF lv_mode = 'N'.
    IF i_queue-is_new <> 'X'.
*     Come oggi: una modifica di un OdA senza processo ne' storico non
*     avvia niente (es. OdA precedenti al WF, o rilasciati in automatico).
      e_action = 'N'.
      e_return = 'OdA modificato senza processo: nessuna azione'.
      RETURN.
    ENDIF.

    SELECT SINGLE parvaluex FROM /ewf/parametri INTO lv_auto_rel
      WHERE keypar = 'WF_AUTO_REL_ODA'.
    IF lv_auto_rel = 'X'.
      CALL FUNCTION 'BAPI_PO_RELEASE'
        EXPORTING
          purchaseorder            = lv_ebeln
          po_rel_code              = lv_rel_code
        TABLES
          return                   = lt_return
        EXCEPTIONS
          authority_check_fail     = 1
          document_not_found       = 2
          enqueue_fail             = 3
          prerequisite_fail        = 4
          release_already_posted   = 5
          responsibility_fail      = 6
          no_release_already       = 7
          no_new_release_indicator = 8
          OTHERS                   = 9.
      CASE sy-subrc.
        WHEN 0 OR 5.
          e_action = 'N'.
          e_return = 'Rilasciato automaticamente (WF_AUTO_REL_ODA)'.
        WHEN 3.
*         L'utente ha ancora l'OdA aperto in modifica: si ritenta.
          e_error  = 'X'.
          e_return = 'Rilascio automatico: OdA bloccato, si ritenta'.
        WHEN OTHERS.
          e_action = 'N'.
          e_return = 'Rilascio automatico non eseguito (eccezione BAPI_PO_RELEASE)'.
      ENDCASE.
      RETURN.
    ENDIF.

    IF lv_netwr >= lc_limit.
      e_action = 'A'.
    ELSE.
      e_action = 'N'.
      e_return = 'Valore sotto il limite del WF'.
    ENDIF.
    RETURN.
  ENDIF.

*--- processo in corso: oltre il primo step? ----------------------------------------
  IF lv_mode = 'R'.
    SELECT SINGLE z_wf_step FROM /ewf/taskrun INTO lv_current
      WHERE z_wf_procid = ls_procrun-z_wf_procid
        AND z_dt_stop   = '00000000'.
    SELECT SINGLE z_wf_step FROM /ewf/task INTO lv_first
      WHERE z_wf_processo   = ls_procrun-z_wf_processo
        AND z_wf_first_step = 'X'.
    IF lv_current <= lv_first.
      e_action = 'N'.
      e_return = 'Processo ancora al primo step: nessun riavvio'.
      RETURN.
    ENDIF.
  ENDIF.

*--- modifica rilevante? ---------------------------------------------------------------
  IF lv_netwr <= lc_limit.
    e_action = 'N'.
    e_return = 'Valore sotto il limite del WF'.
    RETURN.
  ENDIF.

  IF lv_netwr > lv_netwr_old.
    lv_relevant = abap_true.
  ELSE.
*   Documenti di modifica dell'utente intorno al salvataggio. Nel metodo
*   CLOSE la finestra era "ultimi 30 secondi da adesso"; qui l'evento puo'
*   essere elaborato minuti dopo, quindi si parte dall'ora dell'evento.
    CALL FUNCTION 'GET_SYSTEM_TIMEZONE'
      IMPORTING
        timezone = lv_systz
      EXCEPTIONS
        OTHERS   = 1.
    lv_event_ts = i_queue-created_at.
    lv_from = cl_abap_tstmp=>subtractsecs( tstmp = lv_event_ts secs = lc_window ).
    lv_to   = cl_abap_tstmp=>add( tstmp = lv_event_ts secs = lc_window ).
    CONVERT TIME STAMP lv_from TIME ZONE lv_systz INTO DATE lv_date.
    lv_objid = lv_ebeln.

    SELECT changenr udate utime FROM cdhdr INTO TABLE lt_cdhdr
      WHERE objectclas = 'EINKBELEG'
        AND objectid   = lv_objid
        AND username   = i_queue-uname
        AND udate     >= lv_date.

    LOOP AT lt_cdhdr INTO ls_cdhdr.
      CONVERT DATE ls_cdhdr-udate TIME ls_cdhdr-utime
        INTO TIME STAMP lv_ts TIME ZONE lv_systz.
      CHECK lv_ts BETWEEN lv_from AND lv_to.
      SELECT fname value_new value_old FROM cdpos INTO TABLE lt_cdpos
        WHERE objectclas = 'EINKBELEG'
          AND objectid   = lv_objid
          AND changenr   = ls_cdhdr-changenr
          AND tabname    = 'EKKO'
          AND ( fname = 'INCO1' OR fname = 'LIFNR'
             OR fname = 'WAERS' OR fname = 'ZTERM' ).
      LOOP AT lt_cdpos INTO ls_cdpos.
        IF ls_cdpos-value_new <> ls_cdpos-value_old.
          lv_relevant = abap_true.
          EXIT.
        ENDIF.
      ENDLOOP.
      IF lv_relevant = abap_true.
        EXIT.
      ENDIF.
    ENDLOOP.
  ENDIF.

  IF lv_relevant = abap_false.
    e_action = 'N'.
    e_return = 'Modifica non rilevante per il WF'.
    RETURN.
  ENDIF.

  IF lv_mode = 'R'.
    e_action = 'R'.
    e_note   = 'Riavvio per Ordine Acquisto modificato'.
  ELSE.
*   Processo concluso: nessun processo aperto, quindi l'elaboratore avvia
*   un processo nuovo con /EWF/WF_START_ODA.
    e_action = 'A'.
  ENDIF.

ENDFUNCTION.
