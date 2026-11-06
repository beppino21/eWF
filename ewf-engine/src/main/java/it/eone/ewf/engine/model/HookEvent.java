package it.eone.ewf.engine.model;

/**
 * Eventi del task a cui si aggancia un handler.
 * BEFORE_ACTION ex FM_EXANTE, ON_OK ex FM_OK, ON_KO ex FM_KO,
 * ON_ACTIVATE ex FM_EXPOST, BUTTON ex FM_BUTTON01.
 */
public enum HookEvent { BEFORE_ACTION, ON_OK, ON_KO, ON_ACTIVATE, BUTTON }
