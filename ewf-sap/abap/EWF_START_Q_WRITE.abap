FUNCTION /ewf/start_q_write.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   MODULO DI AGGIORNAMENTO (avvio immediato)
*"  IMPORTING
*"     VALUE(I_TIPODOC)  TYPE  /EWF/WF_TIPODOC
*"     VALUE(I_DOCUMENT) TYPE  /EWF/WF_DOCUMENT
*"     VALUE(I_UNAME)    TYPE  SYUNAME
*"     VALUE(I_TCODE)    TYPE  SYTCODE
*"     VALUE(I_EVENT)    TYPE  CHAR10 DEFAULT 'TOUCHED'
*"     VALUE(I_IS_NEW)   TYPE  CHAR1 OPTIONAL
*"     VALUE(I_EXISTS)   TYPE  CHAR1 DEFAULT 'X'
*"     VALUE(I_NETWR)    TYPE  NETWR OPTIONAL
*"     VALUE(I_WAERS)    TYPE  WAERS OPTIONAL
*"  TABLES
*"      IT_CONTEXT STRUCTURE  /EWF/S_ENG_KV OPTIONAL
*"      IT_ITEMS   STRUCTURE  /EWF/S_DOC_ITEM OPTIONAL
*"----------------------------------------------------------------------
* Scrive un evento nella coda degli avvii eWF: "il documento e' stato
* toccato", con la fotografia del documento al momento del salvataggio
* (attributi e posizioni). Il punto di aggancio DESCRIVE, il motore
* DECIDE: qui nessuna logica di processo.
*
* Da chiamare IN UPDATE TASK dai punti di aggancio (BAdI, exit, BTE):
* la riga nasce nella stessa LUW del documento. Se il salvataggio va in
* rollback, la riga non esiste.
*
* I_UNAME, I_TCODE vanno passati dal chiamante (SY-UNAME, SY-TCODE): nel
* processo di aggiornamento SY-TCODE non e' piu' quello dell'utente.
* I_IS_NEW  'X' documento creato in questo salvataggio (fatto, non regola)
* I_EXISTS  ' ' documento cancellato / senza posizioni attive
* I_NETWR, I_WAERS, IT_CONTEXT, IT_ITEMS: dall'estrattore del tipo
*   documento (es. /EWF/CTX_PO).
*
* /EWF/START_CFG decide chi elabora (J Java, L legacy, N non accodare).
* Tipo documento non configurato = L (comportamento di oggi).
*----------------------------------------------------------------------
  DATA: ls_q    TYPE /ewf/start_q,
        ls_cfg  TYPE /ewf/start_cfg,
        lt_qa   TYPE STANDARD TABLE OF /ewf/start_qa,
        ls_qa   TYPE /ewf/start_qa,
        lt_qi   TYPE STANDARD TABLE OF /ewf/start_qi,
        ls_qi   TYPE /ewf/start_qi.

  SELECT SINGLE * FROM /ewf/start_cfg INTO ls_cfg
    WHERE tipodoc = i_tipodoc.
  IF sy-subrc <> 0.
    ls_cfg-engine = 'L'.
    ls_cfg-modo   = 'P'.
  ENDIF.
  CHECK ls_cfg-engine <> 'N'.

  TRY.
      ls_q-qid = cl_system_uuid=>create_uuid_c32_static( ).
    CATCH cx_uuid_error.
      MESSAGE x000(0k) WITH 'eWF: UUID non generato'.
  ENDTRY.
  ls_q-tipodoc  = i_tipodoc.
  ls_q-document = i_document.
  ls_q-event    = i_event.
  ls_q-uname    = i_uname.
  ls_q-tcode    = i_tcode.
  ls_q-engine   = ls_cfg-engine.
  ls_q-is_new   = i_is_new.
  ls_q-exist   = i_exists.
  ls_q-netwr    = i_netwr.
  ls_q-waers    = i_waers.
  GET TIME STAMP FIELD ls_q-created_at.
  INSERT /ewf/start_q FROM ls_q.

  LOOP AT it_context.
    CLEAR ls_qa.
    ls_qa-qid   = ls_q-qid.
    ls_qa-name  = it_context-name.
    ls_qa-value = it_context-value.
    APPEND ls_qa TO lt_qa.
  ENDLOOP.
  SORT lt_qa BY name.
  DELETE ADJACENT DUPLICATES FROM lt_qa COMPARING name.
  IF lt_qa IS NOT INITIAL.
    INSERT /ewf/start_qa FROM TABLE lt_qa.
  ENDIF.

  LOOP AT it_items.
    CLEAR ls_qi.
    MOVE-CORRESPONDING it_items TO ls_qi.
    ls_qi-qid = ls_q-qid.
    APPEND ls_qi TO lt_qi.
  ENDLOOP.
  SORT lt_qi BY item.
  DELETE ADJACENT DUPLICATES FROM lt_qi COMPARING item.
  IF lt_qi IS NOT INITIAL.
    INSERT /ewf/start_qi FROM TABLE lt_qi.
  ENDIF.

  IF ls_cfg-engine = 'L' AND ls_cfg-modo = 'I'.
*   Ponte legacy: sveglia l'elaboratore dopo l'aggiornamento riuscito.
    CALL FUNCTION '/EWF/START_Q_KICK'
      IN BACKGROUND TASK
      DESTINATION 'NONE'.
  ENDIF.

ENDFUNCTION.
