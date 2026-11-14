FUNCTION /ewf/start_q_process_one.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:
*"  IMPORTING
*"     VALUE(I_QID) TYPE  CHAR32
*"  EXPORTING
*"     VALUE(E_STATUS)  TYPE  CHAR1
*"     VALUE(E_MESSAGE) TYPE  CHAR255
*"----------------------------------------------------------------------
* Elabora UNA riga legacy della coda degli avvii e ne registra l'esito.
* Si puo' provare da SE37 indicando un QID.
*
*  1. instradatore del tipo documento (/EWF/START_CFG-ROUTER_FM, default
*     /EWF/START_ROUTE_STD): azione, destinazione, processo, chiave legacy.
*     Quello che fa l'instradatore viene confermato (COMMIT) subito.
*  2. processo legacy aperto sul documento?
*  3. azione:
*       A  aperto -> niente; non aperto -> funzione di avvio
*       R  aperto -> funzione di riavvio; non aperto -> funzione di avvio
*       N  niente
*  4. errore della funzione legacy -> ROLLBACK WORK: niente processi a
*     meta'. Poi l'esito viene scritto sulla riga (COMMIT).
*
* Chiude ogni riga con un COMMIT: da NON chiamare dentro una LUW
* applicativa (update task, tRFC). La chiama /EWF/START_Q_PROCESS.
*----------------------------------------------------------------------
  DATA: ls_q        TYPE /ewf/start_q,
        ls_cfg      TYPE /ewf/start_cfg,
        ls_tgt      TYPE /ewf/start_tgt,
        ls_procrun  TYPE /ewf/procrun,
        lv_router   TYPE rs38l_fnam,
        lv_action   TYPE char1,
        lv_target   TYPE char10,
        lv_processo TYPE /ewf/wf_processo,
        lv_document TYPE /ewf/wf_document,
        lv_note     TYPE char70,
        lv_error    TYPE char1,
        lv_return   TYPE char80,
        lv_ptipodoc TYPE /ewf/processo-z_wf_tipodoc,
        lv_open     TYPE /ewf/wf_procid,
        lv_procid   TYPE /ewf/wf_procid,
        lv_skip     TYPE char1,
        lv_nomsg    TYPE char1,
        lv_done     TYPE char1,
        lx_call     TYPE REF TO cx_sy_dyn_call_error.

  CLEAR: e_status, e_message.

  SELECT SINGLE * FROM /ewf/start_q INTO ls_q WHERE qid = i_qid.
  IF sy-subrc <> 0.
    e_status  = 'E'.
    e_message = 'Riga di coda inesistente'.
    RETURN.
  ENDIF.

* La memoria ABAP non deve passare da un documento all'altro:
* /EWF/WF_START legge WF_NETWR / WF_WAERS se ci sono.
  FREE MEMORY.

*--- 1. instradatore ---------------------------------------------------
  SELECT SINGLE * FROM /ewf/start_cfg INTO ls_cfg
    WHERE tipodoc = ls_q-tipodoc.
  lv_router = ls_cfg-router_fm.
  IF lv_router IS INITIAL.
    lv_router = '/EWF/START_ROUTE_STD'.
  ENDIF.

  TRY.
      CALL FUNCTION lv_router
        EXPORTING
          i_queue    = ls_q
        IMPORTING
          e_action   = lv_action
          e_target   = lv_target
          e_processo = lv_processo
          e_document = lv_document
          e_note     = lv_note
          e_error    = lv_error
          e_return   = lv_return.
    CATCH cx_sy_dyn_call_error INTO lx_call.
      lv_error  = 'X'.
      lv_return = lx_call->get_text( ).
  ENDTRY.

  IF lv_error = 'X'.
    ROLLBACK WORK.
    e_status = 'E'.
    CONCATENATE 'Instradatore' lv_router ':' lv_return
      INTO e_message SEPARATED BY space.
  ELSE.
    COMMIT WORK.                       " effetti dell'instradatore
    IF lv_document IS INITIAL.
      lv_document = ls_q-document.
    ENDIF.
    IF lv_target IS INITIAL.
      lv_target = 'LEGACY'.
    ENDIF.
  ENDIF.

*--- 2/3. azione --------------------------------------------------------
  IF e_status IS INITIAL.
    CASE lv_action.
      WHEN 'N'.
        e_status  = 'D'.
        lv_action = 'N'.
        e_message = lv_return.
        IF e_message IS INITIAL.
          e_message = 'Nessuna azione'.
        ENDIF.

      WHEN 'A' OR 'R'.
        IF lv_processo IS INITIAL.
          e_status  = 'E'.
          e_message = 'L''instradatore non ha indicato il processo'.
        ELSE.
          SELECT SINGLE * FROM /ewf/start_tgt INTO ls_tgt
            WHERE target = lv_target.
          IF sy-subrc <> 0.
            e_status = 'E'.
            CONCATENATE 'Destinazione' lv_target 'non configurata in /EWF/START_TGT'
              INTO e_message SEPARATED BY space.
          ENDIF.
        ENDIF.

        IF e_status IS INITIAL.
*         Processo aperto sullo stesso documento e tipo documento
*         (task con data di fine vuota, come in /EWF/WF_START).
          SELECT SINGLE z_wf_tipodoc FROM /ewf/processo INTO lv_ptipodoc
            WHERE z_wf_processo = lv_processo.
          CLEAR lv_open.
          SELECT z_wf_procid FROM /ewf/taskrun INTO lv_open UP TO 1 ROWS
            WHERE z_wf_document = lv_document
              AND z_wf_tipodoc  = lv_ptipodoc
              AND z_dt_stop     = '00000000'.
          ENDSELECT.

          IF lv_open IS NOT INITIAL AND lv_action = 'A'.
            e_status  = 'D'.
            lv_action = 'N'.
            lv_procid = lv_open.
            CONCATENATE 'Processo' lv_open 'gia'' in corso: nessuna azione'
              INTO e_message SEPARATED BY space.

          ELSEIF lv_open IS NOT INITIAL.          " R: riavvio
            IF ls_tgt-restart_fm IS INITIAL.
              e_status = 'E'.
              CONCATENATE 'Riavvio non configurato per' lv_target
                INTO e_message SEPARATED BY space.
            ELSE.
              SELECT SINGLE * FROM /ewf/procrun INTO ls_procrun
                WHERE z_wf_procid = lv_open.
              IF lv_note IS INITIAL.
                CONCATENATE 'Documento modificato da' ls_q-uname
                  INTO lv_note SEPARATED BY space.
              ENDIF.
              CLEAR: lv_error, lv_return.
              TRY.
                  CALL FUNCTION ls_tgt-restart_fm
                    EXPORTING
                      i_process = ls_procrun
                      i_type    = lv_note
                    IMPORTING
                      e_error   = lv_error
                      e_return  = lv_return.
                CATCH cx_sy_dyn_call_error INTO lx_call.
                  lv_error  = 'X'.
                  lv_return = lx_call->get_text( ).
              ENDTRY.
              lv_action = 'R'.
              lv_procid = lv_open.
              lv_done   = 'X'.
            ENDIF.

          ELSE.                                   " A o R: avvio
            IF ls_tgt-start_fm IS INITIAL.
              e_status = 'E'.
              CONCATENATE 'Avvio non configurato per' lv_target
                INTO e_message SEPARATED BY space.
            ELSE.
              CLEAR: lv_error, lv_return, lv_procid.
              TRY.
                  CALL FUNCTION ls_tgt-start_fm
                    EXPORTING
                      i_document   = lv_document
                      i_processo   = lv_processo
                      i_utente     = ls_q-uname
                    IMPORTING
                      e_return     = lv_return
                      e_error      = lv_error
                      e_procid     = lv_procid
                      e_skip       = lv_skip
                      e_no_message = lv_nomsg.
                CATCH cx_sy_dyn_call_error INTO lx_call.
                  lv_error  = 'X'.
                  lv_return = lx_call->get_text( ).
              ENDTRY.
              lv_action = 'S'.
              lv_done   = 'X'.
            ENDIF.
          ENDIF.

*         Esito della funzione legacy: tutto o niente.
          IF lv_done = 'X'.
            IF lv_error = 'X'.
              ROLLBACK WORK.
              e_status  = 'E'.
              e_message = lv_return.
              CLEAR lv_procid.
              IF lv_action = 'R'.
                lv_procid = lv_open.
              ENDIF.
            ELSE.
              COMMIT WORK.
              e_status  = 'D'.
              e_message = lv_return.
              IF e_message IS INITIAL.
                IF lv_action = 'S'.
                  CONCATENATE 'Avviato processo' lv_procid
                    INTO e_message SEPARATED BY space.
                ELSE.
                  CONCATENATE 'Riavviato processo' lv_procid
                    INTO e_message SEPARATED BY space.
                ENDIF.
              ENDIF.
            ENDIF.
          ENDIF.
        ENDIF.

      WHEN OTHERS.
        e_status = 'E'.
        CONCATENATE 'Azione' lv_action 'sconosciuta (attese A, R, N)'
          INTO e_message SEPARATED BY space.
    ENDCASE.
  ENDIF.

*--- 4. esito sulla riga ---------------------------------------------------
  IF e_status = 'E' AND e_message IS INITIAL.
    e_message = 'Errore senza messaggio dalla funzione legacy'.
  ENDIF.
  ls_q-status   = e_status.
  ls_q-message  = e_message.
  ls_q-procid   = lv_procid.
  ls_q-target   = lv_target.
  ls_q-processo = lv_processo.
  IF e_status = 'D'.
    ls_q-action = lv_action.
  ELSE.
    CLEAR ls_q-action.
  ENDIF.
  ls_q-attempts = ls_q-attempts + 1.
  GET TIME STAMP FIELD ls_q-processed_at.
  UPDATE /ewf/start_q FROM ls_q.
  COMMIT WORK.

ENDFUNCTION.
