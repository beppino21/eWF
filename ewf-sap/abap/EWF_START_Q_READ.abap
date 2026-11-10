FUNCTION /ewf/start_q_read.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (FM ABILITATA RFC)
*"  IMPORTING
*"     VALUE(I_MAX)          TYPE  INT4 DEFAULT 50
*"     VALUE(I_MAX_ATTEMPTS) TYPE  INT4 DEFAULT 20
*"  TABLES
*"      ET_QUEUE STRUCTURE  /EWF/START_Q
*"----------------------------------------------------------------------
* Eventi da elaborare: nuovi ed errori da ritentare, nell'ordine in cui
* sono nati. Dopo I_MAX_ATTEMPTS tentativi un errore resta fermo in coda
* (visibile in SE16 / monitor) finche' qualcuno non lo rimette a ' '.
*----------------------------------------------------------------------
  REFRESH et_queue.
  SELECT * FROM /ewf/start_q INTO TABLE et_queue
    UP TO i_max ROWS
    WHERE ( status = space OR status = 'E' )
      AND attempts < i_max_attempts
    ORDER BY created_at qid.

ENDFUNCTION.
