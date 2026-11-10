FUNCTION /ewf/start_q_confirm.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (FM ABILITATA RFC)
*"  TABLES
*"      IT_RESULT STRUCTURE  /EWF/S_START_RES
*"----------------------------------------------------------------------
* Registra l'esito dell'elaborazione eWF: D fatto, W fatto con avviso,
* E errore (la riga verra' riletta e ritentata).
*----------------------------------------------------------------------
  DATA lv_now TYPE timestampl.

  GET TIME STAMP FIELD lv_now.
  LOOP AT it_result.
    UPDATE /ewf/start_q
       SET status       = it_result-status
           message      = it_result-message
           procid       = it_result-procid
           processed_at = lv_now
           attempts     = attempts + 1
     WHERE qid = it_result-qid.
  ENDLOOP.
  COMMIT WORK.

ENDFUNCTION.
