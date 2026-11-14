FUNCTION /ewf/wf_start_oda.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (stessa interfaccia di /EWF/WF_START)
*"  IMPORTING
*"     REFERENCE(I_DOCUMENT) TYPE  /EWF/WF_DOCUMENT
*"     REFERENCE(I_PROCESSO) TYPE  /EWF/WF_PROCESSO
*"     REFERENCE(I_UTENTE) TYPE  /EWF/WF_UTENTE OPTIONAL
*"  EXPORTING
*"     REFERENCE(E_RETURN) TYPE  CHAR80
*"     REFERENCE(E_ERROR) TYPE  CHAR1
*"     REFERENCE(E_PROCID) TYPE  /EWF/WF_PROCID
*"     REFERENCE(E_SKIP) TYPE  CHAR1
*"     REFERENCE(E_NO_MESSAGE) TYPE  CHAR1
*"----------------------------------------------------------------------
* Avvio processo OdA (destinazione ODA in /EWF/START_TGT).
* /EWF/WF_START standard, poi il passo che il metodo CLOSE faceva dopo
* un avvio riuscito su un OdA gia' passato dal WF (processo storicizzato):
* se l'OdA risulta rilasciato (FRGKE = 1), il messaggio ZNEU viene
* rimesso da elaborare, altrimenti errore ME 175.
* Nessun COMMIT qui: lo fa l'elaboratore della coda, tutto o niente.
*----------------------------------------------------------------------
  DATA: lv_ebeln TYPE ebeln,
        lv_frgke TYPE ekko-frgke,
        ls_nast  TYPE nast.

  CALL FUNCTION '/EWF/WF_START'
    EXPORTING
      i_document   = i_document
      i_processo   = i_processo
      i_utente     = i_utente
    IMPORTING
      e_return     = e_return
      e_error      = e_error
      e_procid     = e_procid
      e_skip       = e_skip
      e_no_message = e_no_message.
  CHECK e_error <> 'X'.

  SELECT COUNT(*) FROM /ewf/procrunhst
    WHERE z_wf_tipodoc = 'F' AND z_wf_document = i_document.
  CHECK sy-dbcnt > 0.

  lv_ebeln = i_document.
  SELECT SINGLE frgke FROM ekko INTO lv_frgke WHERE ebeln = lv_ebeln.
  CHECK lv_frgke = '1'.

  SELECT SINGLE * FROM nast INTO ls_nast
    WHERE kappl = 'EF' AND objky = lv_ebeln AND kschl = 'ZNEU'.
  CHECK sy-subrc = 0.
  CLEAR: ls_nast-datvr, ls_nast-uhrvr, ls_nast-tdreceiver,
         ls_nast-optarcnr, ls_nast-cmfpnr.
  ls_nast-vstat = '0'.
  ls_nast-erdat = sy-datum.
  ls_nast-eruhr = sy-uzeit.
  INSERT nast FROM ls_nast.

ENDFUNCTION.
