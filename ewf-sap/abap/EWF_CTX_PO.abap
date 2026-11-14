FUNCTION /ewf/ctx_po.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:
*"  IMPORTING
*"     VALUE(IS_EKKO) TYPE  EKKO
*"  EXPORTING
*"     VALUE(E_EXISTS) TYPE  CHAR1
*"     VALUE(E_NETWR)  TYPE  NETWR
*"     VALUE(E_WAERS)  TYPE  WAERS
*"  TABLES
*"      IT_EKPO    STRUCTURE  EKPO
*"      ET_CONTEXT STRUCTURE  /EWF/S_ENG_KV
*"      ET_ITEMS   STRUCTURE  /EWF/S_DOC_ITEM
*"----------------------------------------------------------------------
* Estrattore del contesto eWF per l'ordine d'acquisto (tipo documento F).
*
* Unico punto in cui si decide QUALI attributi descrivono un OdA:
*  - lo chiama il punto di aggancio (BAdI ME_PROCESS_PO_CUST~POST) con i
*    dati in memoria, appena prima del salvataggio -> coda degli avvii;
*  - lo chiama /EWF/DOC_INFO con i dati letti dal DB -> lettura su
*    richiesta del motore (avvio manuale, riavvio, riserva).
* Cosi' gli attributi sono identici da qualunque parte arrivino.
*
* Gira dentro il salvataggio dell'utente: SOLO calcoli sui dati
* ricevuti, nessuna lettura dal DB. Un attributo che richiede letture
* va calcolato altrove (motore Java o /EWF/DOC_INFO).
*
* Attributi (nomi = campi SAP; il motore li espone anche come sap.<NOME>):
*   BUKRS EKORG EKGRP BSART LIFNR       testata
*   WERKS                               divisione della prima posizione
*                                       attiva (come /EWF/WF_BUKRS_WERKS)
* Posizioni attive (LOEKZ vuoto): quantita' e valore netto.
* Valore: somma delle posizioni attive, nella divisa dell'OdA.
* E_EXISTS = ' ' se l'OdA e' cancellato o non ha posizioni attive.
*
* Nuovi attributi: si aggiungono qui, e arrivano al motore senza
* modifiche alla coda (sono righe di /EWF/START_QA).
*----------------------------------------------------------------------
  DATA: ls_kv    TYPE /ewf/s_eng_kv,
        ls_item  TYPE /ewf/s_doc_item,
        lv_werks TYPE ewerk.

  DEFINE add_ctx.
    IF &2 IS NOT INITIAL.
      CLEAR ls_kv.
      ls_kv-name  = &1.
      ls_kv-value = &2.
      APPEND ls_kv TO et_context.
    ENDIF.
  END-OF-DEFINITION.

  CLEAR: e_exists, e_netwr, e_waers.
  REFRESH: et_context, et_items.

  e_waers = is_ekko-waers.

  SORT it_ekpo BY ebelp.
  LOOP AT it_ekpo WHERE loekz IS INITIAL.
    IF lv_werks IS INITIAL.
      lv_werks = it_ekpo-werks.
    ENDIF.
    CLEAR ls_item.
    ls_item-item  = it_ekpo-ebelp.
    ls_item-menge = it_ekpo-menge.
    ls_item-meins = it_ekpo-meins.
    ls_item-netwr = it_ekpo-netwr.
    ls_item-waers = is_ekko-waers.
    APPEND ls_item TO et_items.
    e_netwr = e_netwr + it_ekpo-netwr.
  ENDLOOP.

  IF is_ekko-loekz IS INITIAL AND et_items[] IS NOT INITIAL.
    e_exists = 'X'.
  ENDIF.

  add_ctx 'BUKRS' is_ekko-bukrs.
  add_ctx 'EKORG' is_ekko-ekorg.
  add_ctx 'EKGRP' is_ekko-ekgrp.
  add_ctx 'BSART' is_ekko-bsart.
  add_ctx 'LIFNR' is_ekko-lifnr.
  add_ctx 'WERKS' lv_werks.

ENDFUNCTION.
