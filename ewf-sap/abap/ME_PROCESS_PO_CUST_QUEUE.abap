*----------------------------------------------------------------------*
* BAdI ME_PROCESS_PO_CUST - punto di aggancio eWF per l'ordine d'acquisto.
*
* Solo POST: descrive l'OdA (estrattore /EWF/CTX_PO, dati in memoria) e
* lo mette in coda, nella stessa LUW del salvataggio. Nessun filtro,
* nessuna scelta di processo, nessun COMMIT, nessun popup: decide il
* motore, in base agli attributi e alle sue regole.
*
* Sostituisce la logica WF attuale di POST e CLOSE (fare prima una copia
* dell'implementazione). CLOSE non serve piu' per il WF.
*----------------------------------------------------------------------*

METHOD if_ex_me_process_po_cust~post.
  DATA: ls_header   TYPE mepoheader,
        lt_items    TYPE purchase_order_items,
        ls_itemref  LIKE LINE OF lt_items,
        ls_item     TYPE mepoitem,
        ls_ekko     TYPE ekko,
        lt_ekpo     TYPE STANDARD TABLE OF ekpo,
        ls_ekpo     TYPE ekpo,
        lt_context  TYPE STANDARD TABLE OF /ewf/s_eng_kv,
        lt_docitems TYPE STANDARD TABLE OF /ewf/s_doc_item,
        lv_exists   TYPE char1,
        lv_netwr    TYPE netwr,
        lv_waers    TYPE waers,
        lv_is_new   TYPE char1,
        lv_ebeln    TYPE ebeln,
        lv_document TYPE /ewf/wf_document.

  CHECK im_ebeln IS NOT INITIAL.

* Dati come stanno per essere salvati.
  ls_header = im_header->get_data( ).
  MOVE-CORRESPONDING ls_header TO ls_ekko.
  ls_ekko-ebeln = im_ebeln.
  lt_items = im_header->get_items( ).
  LOOP AT lt_items INTO ls_itemref.
    ls_item = ls_itemref-item->get_data( ).
    MOVE-CORRESPONDING ls_item TO ls_ekpo.
    ls_ekpo-ebeln = im_ebeln.
    APPEND ls_ekpo TO lt_ekpo.
  ENDLOOP.

  CALL FUNCTION '/EWF/CTX_PO'
    EXPORTING
      is_ekko    = ls_ekko
    IMPORTING
      e_exists   = lv_exists
      e_netwr    = lv_netwr
      e_waers    = lv_waers
    TABLES
      it_ekpo    = lt_ekpo
      et_context = lt_context
      et_items   = lt_docitems.

* Nuovo = non ancora sul DB (il salvataggio non e' ancora avvenuto).
  SELECT SINGLE ebeln FROM ekko INTO lv_ebeln WHERE ebeln = im_ebeln.
  IF sy-subrc <> 0.
    lv_is_new = 'X'.
  ENDIF.

  lv_document = im_ebeln.
  CALL FUNCTION '/EWF/START_Q_WRITE' IN UPDATE TASK
    EXPORTING
      i_tipodoc  = 'F'
      i_document = lv_document
      i_uname    = sy-uname
      i_tcode    = sy-tcode
      i_is_new   = lv_is_new
      i_exists   = lv_exists
      i_netwr    = lv_netwr
      i_waers    = lv_waers
    TABLES
      it_context = lt_context
      it_items   = lt_docitems.

ENDMETHOD.
