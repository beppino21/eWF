package it.eone.ewf.sap.migration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configurazione /EWF/ letta da SAP: per ogni tabella, le righe come mappe campo -> valore (stringhe gia' ripulite).
 * Separata dalla lettura JCo cosi' la mappatura si prova anche senza SAP.
 */
public final class LegacyConfig {
    public static final String PROCESSO = "ET_PROCESSO", PROCTYPE = "ET_PROCTYPE", TASK = "ET_TASK",
            TASKOPZ = "ET_TASKOPZ", ROLES = "ET_ROLES", USER0 = "ET_USER0", TEAM = "ET_TEAM",
            USER_TASK = "ET_USER_TASK", SOSTITUTI = "ET_SOSTITUTI", USR_AUTH = "ET_USR_AUTH",
            CLI_USER = "ET_CLI_USER", CDO_USERGRP = "ET_CDO_USERGRP", CDO_AUTH = "ET_CDO_AUTH";

    public static final List<String> TABLES = List.of(PROCESSO, PROCTYPE, TASK, TASKOPZ, ROLES, USER0, TEAM,
            USER_TASK, SOSTITUTI, USR_AUTH, CLI_USER, CDO_USERGRP, CDO_AUTH);

    private final Map<String, List<Map<String, String>>> tables = new LinkedHashMap<>();

    public LegacyConfig put(String table, List<Map<String, String>> rows) {
        tables.put(table, List.copyOf(rows));
        return this;
    }

    public List<Map<String, String>> rows(String table) {
        return tables.getOrDefault(table, List.of());
    }
}
