FUNCTION /ewf/doc_info.
*"----------------------------------------------------------------------
*"*"Interfaccia locale:   (FM ABILITATA RFC: tutti i parametri "Pass Value")
*"  IMPORTING
*"     VALUE(I_TIPODOC)  TYPE  /EWF/WF_TIPODOC
*"     VALUE(I_DOCUMENT) TYPE  /EWF/WF_DOCUMENT
*"  EXPORTING
*"     VALUE(E_EXISTS)   TYPE  CHAR1
*"     VALUE(E_NETWR)    TYPE  NETWR
*"     VALUE(E_WAERS)    TYPE  WAERS
*"     VALUE(E_MESSAGE)  TYPE  CHAR80
*"  TABLES
*"      ET_CONTEXT STRUCTURE  /EWF/S_ENG_KV
*"----------------------------------------------------------------------
* Adattatore documento del motore eWF (sola lettura).
* Restituisce: esistenza, valore (divisa standard del mandante, come
* /EWF/WF_DOCVALUE) e attributi per le regole di assegnazione.
* Riusa /EWF/WF_DOCVALUE e /EWF/WF_BUKRS_WERKS: i tipi documento gia'
* aggiunti presso i clienti continuano a funzionare.
*
* E_EXISTS = 'X' documento presente e non cancellato
*          = ' ' inesistente o cancellato (motivo in E_MESSAGE)
* Tipi diversi da F, K, B, FA, FI_DOC: esistenza non verificabile qui,
* E_EXISTS = 'X' con avviso in E_MESSAGE.
*----------------------------------------------------------------------
  DATA: ls_ekko  TYPE ekko,
        lv_ebeln TYPE ekko-ebeln,
        lv_banfn TYPE eban-banfn,
        lv_ekgrp TYPE eban-ekgrp,
        lv_lgort TYPE eban-lgort,
        lv_werks TYPE ewerk,
        lv_bukrs TYPE bukrs,
        lv_gjahr TYPE bkpf-gjahr,
        lv_belnr TYPE bkpf-belnr,
        lv_doc32 TYPE char0032,
        lv_tp    TYPE char01,
        lv_err   TYPE char01,
        ls_kv    TYPE /ewf/s_eng_kv.

  DEFINE add_ctx.
    IF &2 IS NOT INITIAL.
      CLEAR ls_kv.
      ls_kv-name  = &1.
      ls_kv-value = &2.
      APPEND ls_kv TO et_context.
    ENDIF.
  END-OF-DEFINITION.

  CLEAR: e_exists, e_netwr, e_waers, e_message.
  REFRESH et_context.

*--- 1. Esistenza e attributi di testata
  CASE i_tipodoc.
    WHEN 'F' OR 'K'.                                   " OdA / contratto
      lv_ebeln = i_document.
      CALL FUNCTION 'CONVERSION_EXIT_ALPHA_INPUT'
        EXPORTING input  = lv_ebeln
        IMPORTING output = lv_ebeln.
      SELECT SINGLE * FROM ekko INTO ls_ekko WHERE ebeln = lv_ebeln.
      IF sy-subrc <> 0.
        CONCATENATE 'Documento' lv_ebeln 'inesistente' INTO e_message SEPARATED BY space.
        RETURN.
      ENDIF.
      IF ls_ekko-loekz IS NOT INITIAL.
        CONCATENATE 'Documento' lv_ebeln 'cancellato' INTO e_message SEPARATED BY space.
        RETURN.
      ENDIF.
      e_exists = 'X'.
      add_ctx 'EKGRP' ls_ekko-ekgrp.
      add_ctx 'EKORG' ls_ekko-ekorg.
      add_ctx 'LIFNR' ls_ekko-lifnr.
      add_ctx 'BSART' ls_ekko-bsart.

    WHEN 'B'.                                          " RdA
      lv_banfn = i_document.
      CALL FUNCTION 'CONVERSION_EXIT_ALPHA_INPUT'
        EXPORTING input  = lv_banfn
        IMPORTING output = lv_banfn.
*     come WF_USER_TASK: dati della prima posizione non cancellata
      SELECT SINGLE ekgrp lgort FROM eban INTO (lv_ekgrp, lv_lgort)
        WHERE banfn = lv_banfn AND loekz = space.
      IF sy-subrc <> 0.
        CONCATENATE 'Richiesta' lv_banfn 'inesistente o cancellata' INTO e_message SEPARATED BY space.
        RETURN.
      ENDIF.
      e_exists = 'X'.
      add_ctx 'EKGRP' lv_ekgrp.
      add_ctx 'LGORT' lv_lgort.

    WHEN 'FA' OR 'FI_DOC'.                             " documento contabile: SOCIETA ANNO NUMERO
*     stesso formato chiave di /EWF/WF_DOCVALUE: bukrs(4) gjahr+5(4) belnr+10(10)
      lv_bukrs = i_document(4).
      lv_gjahr = i_document+5(4).
      lv_belnr = i_document+10(10).
      SELECT SINGLE bukrs FROM bkpf INTO lv_bukrs
        WHERE bukrs = lv_bukrs AND gjahr = lv_gjahr AND belnr = lv_belnr.
      IF sy-subrc <> 0.
        CONCATENATE 'Documento contabile' i_document 'inesistente' INTO e_message SEPARATED BY space.
        RETURN.
      ENDIF.
      e_exists = 'X'.
      add_ctx 'BUKRS' lv_bukrs.

    WHEN OTHERS.
      e_exists  = 'X'.
      CONCATENATE 'Tipo documento' i_tipodoc ': esistenza non verificata' INTO e_message SEPARATED BY space.
  ENDCASE.

*--- 2. Societa' e divisione: come WF_USER_TASK (prima posizione non cancellata)
  IF i_tipodoc = 'F' OR i_tipodoc = 'K' OR i_tipodoc = 'B'.
    lv_doc32 = i_document.
    lv_tp    = i_tipodoc.
    CALL FUNCTION '/EWF/WF_BUKRS_WERKS'
      EXPORTING
        i1_nr_doc = lv_doc32
        i2_tp_doc = lv_tp
      IMPORTING
        o1_bukrs  = lv_bukrs
        o2_werks  = lv_werks
        o9_errore = lv_err.
    IF lv_err IS INITIAL.
      add_ctx 'BUKRS' lv_bukrs.
      add_ctx 'WERKS' lv_werks.
    ENDIF.
  ENDIF.

*--- 3. Valore nella divisa standard del mandante
  CALL FUNCTION '/EWF/WF_DOCVALUE'
    EXPORTING
      i_document = i_document
      i_tipodoc  = i_tipodoc
    IMPORTING
      e_waers    = e_waers
      e_netwr    = e_netwr.

ENDFUNCTION.
