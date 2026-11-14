FUNCTION /ewf/start_route_std.
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
* Instradatore standard: usato quando /EWF/START_CFG non ne indica uno.
*
* - processo: l'unico processo attivo (non sospeso, non fittizio) per il
*   tipo documento. Nessuno -> nessuna azione. Piu' d'uno -> errore: la
*   scelta dipende dal documento e serve un instradatore specifico.
* - azione A: avvia se sul documento non c'e' un processo aperto,
*   altrimenti niente (nessun riavvio automatico).
* - destinazione LEGACY (/EWF/WF_START).
*----------------------------------------------------------------------
  DATA: lt_processi TYPE STANDARD TABLE OF /ewf/wf_processo,
        lv_n        TYPE i.

  CLEAR: e_action, e_target, e_processo, e_document, e_note,
         e_error, e_return.

  SELECT z_wf_processo FROM /ewf/processo INTO TABLE lt_processi
    WHERE z_wf_tipodoc  = i_queue-tipodoc
      AND z_wf_sospeso  = space
      AND z_wf_fittizio = space.
  lv_n = lines( lt_processi ).

  CASE lv_n.
    WHEN 0.
      e_action = 'N'.
      CONCATENATE 'Nessun processo attivo per il tipo documento' i_queue-tipodoc
        INTO e_return SEPARATED BY space.
    WHEN 1.
      READ TABLE lt_processi INTO e_processo INDEX 1.
      e_action   = 'A'.
      e_target   = 'LEGACY'.
      e_document = i_queue-document.
    WHEN OTHERS.
      e_error = 'X'.
      CONCATENATE 'Piu'' processi attivi per' i_queue-tipodoc
                  ': indicare un instradatore in /EWF/START_CFG'
        INTO e_return SEPARATED BY space.
  ENDCASE.

ENDFUNCTION.
