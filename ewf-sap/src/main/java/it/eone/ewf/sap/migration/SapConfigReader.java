package it.eone.ewf.sap.migration;

import com.sap.conn.jco.*;

import java.util.*;

/** Legge la configurazione /EWF/ chiamando /EWF/ENGINE_EXPORT. */
public final class SapConfigReader {

    public static final String EXPORT_FM = "/EWF/ENGINE_EXPORT";

    public LegacyConfig read(String destinationName) throws JCoException {
        JCoDestination dest = JCoDestinationManager.getDestination(destinationName);
        JCoFunction f = dest.getRepository().getFunction(EXPORT_FM);
        if (f == null) throw new IllegalStateException(EXPORT_FM + " non trovata nel sistema " + destinationName);
        f.execute(dest);
        LegacyConfig cfg = new LegacyConfig();
        for (String name : LegacyConfig.TABLES) {
            JCoTable t = f.getTableParameterList().getTable(name);
            JCoMetaData md = t.getMetaData();
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 0; i < t.getNumRows(); i++) {
                t.setRow(i);
                Map<String, String> row = new LinkedHashMap<>();
                for (int k = 0; k < md.getFieldCount(); k++) {
                    String v = t.getString(md.getName(k));
                    row.put(md.getName(k), v == null ? "" : v.trim());
                }
                rows.add(row);
            }
            cfg.put(name, rows);
        }
        return cfg;
    }
}
