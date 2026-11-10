package it.eone.connect.sync;

import it.eone.connect.db.ConnectSchema;
import it.eone.connect.db.Db;
import it.eone.connect.db.SimpleDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SyncStateTest {

    private SyncState sync;

    @BeforeEach
    void setUp() {
        String url = System.getenv("CONNECT_TEST_DB_URL");
        Assumptions.assumeTrue(url != null, "CONNECT_TEST_DB_URL non impostata");
        Db db = new Db(new SimpleDataSource(url, System.getenv("CONNECT_TEST_DB_USER"), System.getenv("CONNECT_TEST_DB_PASSWORD")));
        db.tx(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS eone_connect CASCADE");
            }
            return null;
        });
        ConnectSchema.migrate(db, null);
        sync = new SyncState(db);
    }

    @Test
    void primaVoltaSenzaWatermarkPoiAvanzaSoloSeVaBene() {
        SyncState.Run r1 = sync.begin("S4_PROD", "EKET");
        assertNull(r1.watermark(), "mai sincronizzato: completa");
        assertEquals("RUNNING", sync.get("S4_PROD", "EKET").orElseThrow().status());
        r1.ok("2026-11-10T10:00:00Z", 120);

        SyncState.Run r2 = sync.begin("S4_PROD", "EKET");
        assertEquals("2026-11-10T10:00:00Z", r2.watermark());
        r2.error("timeout");
        SyncState.State s = sync.get("S4_PROD", "EKET").orElseThrow();
        assertEquals("ERROR", s.status());
        assertEquals("timeout", s.message());
        assertEquals("2026-11-10T10:00:00Z", s.watermark(), "dopo un errore si riparte dallo stesso punto");

        sync.reset("S4_PROD", "EKET");
        assertNull(sync.begin("S4_PROD", "EKET").watermark());
        assertTrue(sync.get("ALTRO", "EKET").isEmpty(), "ogni sistema di origine ha il suo stato");
    }
}
