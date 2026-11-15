package it.eone.ewf.sap.sheet;

import com.sap.conn.jco.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link Rfc} con JCo, sulla destinazione indicata (la stessa di /EWF/DOC_INFO). */
public final class JcoRfc implements Rfc {

    private final String destinationName;

    public JcoRfc(String destinationName) {
        this.destinationName = destinationName;
    }

    @Override
    public Result call(String function, Map<String, String> imports) {
        JCoDestination dest;
        JCoFunction f;
        try {
            dest = JCoDestinationManager.getDestination(destinationName);
            f = dest.getRepository().getFunction(function);
        } catch (JCoException e) {
            throw new IllegalStateException("SAP non raggiungibile (" + destinationName + "): " + e.getMessage(), e);
        }
        if (f == null) throw new FunctionMissing(function);
        imports.forEach((k, v) -> f.getImportParameterList().setValue(k, v));
        try {
            f.execute(dest);
        } catch (JCoException e) {
            throw new IllegalStateException(function + ": " + e.getMessage(), e);
        }
        return new Result() {
            @Override
            public Map<String, String> structure(String name) {
                JCoParameterList ex = f.getExportParameterList();
                if (ex == null || !ex.getMetaData().hasField(name)) return Map.of();
                return record(ex.getStructure(name));
            }

            @Override
            public List<Map<String, String>> table(String name) {
                JCoParameterList tl = f.getTableParameterList();
                if (tl == null || !tl.getMetaData().hasField(name)) return List.of();
                JCoTable t = tl.getTable(name);
                List<Map<String, String>> rows = new ArrayList<>();
                for (int i = 0; i < t.getNumRows(); i++) {
                    t.setRow(i);
                    rows.add(record(t));
                }
                return rows;
            }
        };
    }

    private static Map<String, String> record(JCoRecord r) {
        Map<String, String> m = new LinkedHashMap<>();
        JCoMetaData md = r.getMetaData();
        for (int i = 0; i < md.getFieldCount(); i++) {
            String n = md.getName(i);
            String v = r.getString(n);
            m.put(n, v == null ? "" : v.trim());
        }
        return m;
    }
}
