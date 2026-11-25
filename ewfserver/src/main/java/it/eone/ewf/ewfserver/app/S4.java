package it.eone.ewf.ewfserver.app;

import it.eone.ewf.ewfserver.config.AppConfig;
import it.eone.ewf.s4cloud.PurchaseOrderApi;
import it.eone.ewf.s4cloud.S4Connection;
import it.eone.ewf.s4cloud.S4PurchaseOrderAdapter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Collegamento a S/4HANA Cloud Public (ewf-s4cloud, strada D / D23). Si attiva da solo se in
 * ewf_core.backend_system c'e' un sistema attivo di tipo S4_PUBLIC: connection_ref e' il NAME delle variabili
 * EWF_S4_NAME_URL / _USER / _PASSWD (vedi S4Connection) e, facoltativa, EWF_S4_NAME_INBOX_URL (indirizzo della
 * My Inbox; default: lo stesso host senza "-api", app WorkflowTask-displayInbox).
 *
 * Se la configurazione manca o e' sbagliata l'applicazione parte lo stesso, senza S/4: il motivo e' nel log.
 */
record S4(String backendCode, String name, PurchaseOrderApi api, S4PurchaseOrderAdapter adapter, String inboxUrl) {

    static Optional<S4> fromDatabase(DataSource ds) {
        String code, ref;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT code, connection_ref FROM ewf_core.backend_system
                      WHERE active AND kind = 'S4_PUBLIC' ORDER BY code LIMIT 1""");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            code = rs.getString(1);
            ref = rs.getString(2);
        } catch (SQLException e) {
            System.err.println("[EwfApp] Lettura di ewf_core.backend_system non riuscita: " + e.getMessage());
            return Optional.empty();
        }
        try {
            S4Connection s4 = S4Connection.fromEnv(ref, S4::config);
            PurchaseOrderApi api = new PurchaseOrderApi(s4);
            String inbox = config("EWF_S4_" + ref.toUpperCase() + "_INBOX_URL");
            if (inbox == null || inbox.isBlank()) inbox = defaultInbox(config("EWF_S4_" + ref.toUpperCase() + "_URL"));
            System.out.println("[EwfApp] S/4 Public " + code + " (" + ref + "): OdA via API_PURCHASEORDER_2, My Inbox " + inbox);
            return Optional.of(new S4(code, ref, api, new S4PurchaseOrderAdapter(api), inbox));
        } catch (RuntimeException e) {
            System.err.println("[EwfApp] S/4 Public " + code + " non collegato: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** https://my434383-api.s4hana.cloud.sap -> https://my434383.s4hana.cloud.sap/ui#WorkflowTask-displayInbox */
    static String defaultInbox(String apiUrl) {
        if (apiUrl == null) return null;
        String host = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        return host.replaceFirst("-api\\.", ".") + "/ui#WorkflowTask-displayInbox";
    }

    /** Password solo dall'ambiente; il resto dal file di configurazione o dall'ambiente. */
    private static String config(String key) {
        if (key.endsWith("_PASSWD")) return AppConfig.secret(key);
        return AppConfig.get(key, null);
    }
}
