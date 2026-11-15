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
* Gira dentro il salvataggio dell'utente: calcoli sui dati ricevuti e,
* per le descrizioni, SOLO letture singole per chiave primaria (tabelle
* di anagrafica, quasi tutte bufferizzate). Niente letture di documenti
* o selezioni ampie: quelle vanno nel motore Java o in /EWF/DOC_INFO.
*
* Attributi (nomi = campi SAP; il motore li espone come sap.<NOME>):
*   BUKRS EKORG EKGRP BSART LIFNR       testata
*   WERKS                               divisione della prima posizione
*                                       attiva (come /EWF/WF_BUKRS_WERKS)
*   LIFNR_TXT                           ragione sociale (LFA1-NAME1)
*   WERKS_TXT                           descrizione divisione (T001W-NAME1)
*   EKGRP_TXT                           descrizione gruppo acq. (T024-EKNAM)
*   BSART_TXT                           descrizione tipo OdA (T161T-BATXT)
*   ZTERM_TXT                           descrizione cond. pagamento (TVZBT-VTEXT,
*                                       se manca la spiegazione propria T052U-TEXT1)
* Le descrizioni sono nella lingua di chi salva (sy-langu).
* Le descrizioni servono solo alla lista dell'app: se mancano, l'OdA
* parte lo stesso (nessun errore, attributo semplicemente assente).
* Posizioni attive (LOEKZ vuoto): quantita' e valore netto.
* Valore: somma delle posizioni attive, nella divisa dell'OdA.
* E_EXISTS = ' ' se l'OdA e' cancellato o non ha posizioni attive.
*
* Nuovi attributi: si aggiungono qui, e arrivano al motore senza
* modifiche alla coda (sono righe di /EWF/START_QA).
*----------------------------------------------------------------------
  DATA: ls_kv    TYPE /ewf/s_eng_kv,
        ls_item  TYPE /ewf/s_doc_item,
        lv_werks TYPE ewerk,
        lv_name1 TYPE lfa1-name1,
        lv_wname TYPE t001w-name1,
        lv_eknam TYPE t024-eknam,
        lv_batxt TYPE t161t-batxt,
        lv_bstyp TYPE ekko-bstyp,
        lv_vtext TYPE tvzbt-vtext,
        lv_text1 TYPE t052u-text1.

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

* Descrizioni (letture per chiave primaria)
  IF is_ekko-lifnr IS NOT INITIAL.
    SELECT SINGLE name1 FROM lfa1 INTO lv_name1
      WHERE lifnr = is_ekko-lifnr.
    add_ctx 'LIFNR_TXT' lv_name1.
  ENDIF.
  IF lv_werks IS NOT INITIAL.
    SELECT SINGLE name1 FROM t001w INTO lv_wname
      WHERE werks = lv_werks.
    add_ctx 'WERKS_TXT' lv_wname.
  ENDIF.
  IF is_ekko-ekgrp IS NOT INITIAL.
    SELECT SINGLE eknam FROM t024 INTO lv_eknam
      WHERE ekgrp = is_ekko-ekgrp.
    add_ctx 'EKGRP_TXT' lv_eknam.
  ENDIF.
  IF is_ekko-bsart IS NOT INITIAL.
    lv_bstyp = is_ekko-bstyp.
    IF lv_bstyp IS INITIAL.
      lv_bstyp = 'F'.                    " ordine d'acquisto
    ENDIF.
    SELECT SINGLE batxt FROM t161t INTO lv_batxt
      WHERE spras = sy-langu
        AND bstyp = lv_bstyp
        AND bsart = is_ekko-bsart.
    add_ctx 'BSART_TXT' lv_batxt.
  ENDIF.
  IF is_ekko-zterm IS NOT INITIAL.
    SELECT SINGLE vtext FROM tvzbt INTO lv_vtext
      WHERE spras = sy-langu
        AND zterm = is_ekko-zterm.
    IF lv_vtext IS INITIAL.
*     Condizioni descritte solo con la spiegazione propria (OBB8)
      SELECT text1 FROM t052u INTO lv_text1 UP TO 1 ROWS
        WHERE spras = sy-langu
          AND zterm = is_ekko-zterm
        ORDER BY ztagg.
      ENDSELECT.
      lv_vtext = lv_text1.
    ENDIF.
    add_ctx 'ZTERM_TXT' lv_vtext.
  ENDIF.

ENDFUNCTION.
