FUNCTION /ewf/wf_restart_oda.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (stessa interfaccia di /EWF/WF_RESTART_PROCESS)
*"  IMPORTING
*"     REFERENCE(I_PROCESS) TYPE  /EWF/PROCRUN
*"     REFERENCE(I_TYPE) TYPE  CHAR70
*"  EXPORTING
*"     REFERENCE(E_ERROR) TYPE  CHAR01
*"     REFERENCE(E_RETURN) TYPE  CHAR80
*"----------------------------------------------------------------------
* Riavvio processo OdA (destinazione ODA in /EWF/START_TGT).
* /EWF/WF_RESTART_PROCESS (con la correzione delle parentesi!), poi,
* come nel metodo CLOSE, il valore del processo sale se l'OdA e'
* aumentato. Il valore lo calcola /EWF/START_ROUTE_ODA (memoria
* EWF_ODA_NETWR). Nessun COMMIT qui: lo fa l'elaboratore della coda.
*----------------------------------------------------------------------
  DATA: lv_netwr     TYPE netwr,
        lv_netwr_old TYPE netwr.

  CALL FUNCTION '/EWF/WF_RESTART_PROCESS'
    EXPORTING
      i_process = i_process
      i_type    = i_type
    IMPORTING
      e_error   = e_error
      e_return  = e_return.
  CHECK e_error IS INITIAL.

  IMPORT netwr = lv_netwr FROM MEMORY ID 'EWF_ODA_NETWR'.
  CHECK sy-subrc = 0.
  SELECT MAX( z_wf_netwr ) FROM /ewf/procdoc INTO lv_netwr_old
    WHERE z_wf_tipodoc  = i_process-z_wf_tipodoc
      AND z_wf_document = i_process-z_wf_document.
  IF lv_netwr > lv_netwr_old.
    UPDATE /ewf/procdoc SET z_wf_netwr = lv_netwr
      WHERE z_wf_tipodoc  = i_process-z_wf_tipodoc
        AND z_wf_document = i_process-z_wf_document.
  ENDIF.

ENDFUNCTION.
