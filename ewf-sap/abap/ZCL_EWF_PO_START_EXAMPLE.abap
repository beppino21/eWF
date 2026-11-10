*----------------------------------------------------------------------
* ESEMPIO - BAdI ME_PROCESS_PO_CUST, metodo POST
* Scrive in coda l'evento giusto al salvataggio dell'OdA.
* Da adattare: e' il punto in cui oggi i progetti chiamano /EWF/WF_START.
* Verificare sul sistema la firma di GET_PREVIOUS_DATA (release).
*----------------------------------------------------------------------
METHOD if_ex_me_process_po_cust~post.

  DATA: ls_new   TYPE mepoheader,
        ls_old   TYPE mepoheader,
        lv_old   TYPE abap_bool,
        lv_event TYPE char10,
        lv_doc   TYPE /ewf/wf_document.

  ls_new = im_header->get_data( ).
  lv_old = im_header->get_previous_data( IMPORTING ex_data = ls_old ).

  IF lv_old = abap_false.
*   documento nuovo: sara' eWF a decidere (regole di avvio) se e quale processo serve
    lv_event = 'CREATED'.

  ELSEIF ls_new-loekz IS NOT INITIAL AND ls_old-loekz IS INITIAL.
    lv_event = 'DELETED'.

  ELSEIF ls_new-frggr IS NOT INITIAL.
*   con strategia di rilascio decide SAP: si riapprova solo se il rilascio e' stato azzerato
*   (modificabilita' e tolleranze della strategia) o se e' cambiata la strategia
    IF ( ls_old-frgzu IS NOT INITIAL AND ls_new-frgzu IS INITIAL )
       OR ls_old-frgsx <> ls_new-frgsx.
      lv_event = 'RESET'.
    ENDIF.

  ELSE.
*   senza strategia: eWF confronta valore e quantita' con l'avvio (tolleranza del tipo processo)
    lv_event = 'CHANGED'.
  ENDIF.

  CHECK lv_event IS NOT INITIAL.
  lv_doc = im_ebeln.
  CALL FUNCTION '/EWF/START_Q_WRITE' IN UPDATE TASK
    EXPORTING
      i_tipodoc  = 'F'
      i_document = lv_doc
      i_event    = lv_event.

ENDMETHOD.
