package it.eone.ewf.engine.model;

/** Utente eWF (vista minima usata dal motore). */
public record User(long id, String login, String displayName, String sapUser, boolean active) {}
