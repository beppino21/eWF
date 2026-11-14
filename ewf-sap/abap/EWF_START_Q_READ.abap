FUNCTION /ewf/start_q_read.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (FM ABILITATA RFC)
*"  IMPORTING
*"     VALUE(I_MAX)          TYPE  INT4 DEFAULT 50
*"     VALUE(I_MAX_ATTEMPTS) TYPE  INT4 DEFAULT 20
*"  TABLES
*"      ET_QUEUE STRUCTURE  /EWF/START_Q
*"      ET_ATTR  STRUCTURE  /EWF/START_QA OPTIONAL
*"      ET_ITEMS STRUCTURE  /EWF/START_QI OPTIONAL
*"----------------------------------------------------------------------
* Eventi per il motore Java, nell'ordine in cui sono nati, con la loro
* fotografia del documento (attributi e posizioni, collegati per QID).
* Nuovi ed errori da ritentare; le righe del ponte legacy (ENGINE = L)
* sono escluse.
* Dopo I_MAX_ATTEMPTS tentativi un errore resta fermo in coda (visibile
* in SE16 / monitor) finche' qualcuno non lo rimette a ' '.
*----------------------------------------------------------------------
  REFRESH: et_queue, et_attr, et_items.

  SELECT * FROM /ewf/start_q INTO TABLE et_queue
    UP TO i_max ROWS
    WHERE engine <> 'L'
      AND ( status = space OR status = 'E' )
      AND attempts < i_max_attempts
    ORDER BY created_at qid.
  CHECK et_queue[] IS NOT INITIAL.

  SELECT * FROM /ewf/start_qa INTO TABLE et_attr
    FOR ALL ENTRIES IN et_queue
    WHERE qid = et_queue-qid.

  SELECT * FROM /ewf/start_qi INTO TABLE et_items
    FOR ALL ENTRIES IN et_queue
    WHERE qid = et_queue-qid.

ENDFUNCTION.
