FUNCTION /ewf/start_q_write.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   MODULO DI AGGIORNAMENTO (avvio immediato)
*"  IMPORTING
*"     VALUE(I_TIPODOC)  TYPE  /EWF/WF_TIPODOC
*"     VALUE(I_DOCUMENT) TYPE  /EWF/WF_DOCUMENT
*"     VALUE(I_EVENT)    TYPE  CHAR10
*"     VALUE(I_UNAME)    TYPE  SYUNAME DEFAULT SY-UNAME
*"----------------------------------------------------------------------
* Scrive un evento nella coda degli avvii eWF.
* Da chiamare IN UPDATE TASK dalle exit di salvataggio: la riga nasce
* nella stessa LUW del documento (se il salvataggio va in rollback,
* la riga non esiste). Sostituisce la chiamata diretta a /EWF/WF_START.
*
* I_EVENT: CREATED  documento nuovo da approvare
*          RESET    rilascio azzerato dalla strategia (modifica rilevante)
*          CHANGED  modificato, senza strategia di rilascio
*          DELETED  cancellato
*----------------------------------------------------------------------
  DATA ls_q TYPE /ewf/start_q.

  TRY.
      ls_q-qid = cl_system_uuid=>create_uuid_c32_static( ).
    CATCH cx_uuid_error.
      MESSAGE x000(0k) WITH 'eWF: UUID non generato'.
  ENDTRY.
  ls_q-tipodoc  = i_tipodoc.
  ls_q-document = i_document.
  ls_q-event    = i_event.
  ls_q-uname    = i_uname.
  GET TIME STAMP FIELD ls_q-created_at.
  INSERT /ewf/start_q FROM ls_q.

ENDFUNCTION.
