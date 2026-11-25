package it.eone.ewf.s4cloud;

import it.eone.connect.sync.SyncState;

import java.time.Instant;

/**
 * Punto di ripresa del polling in eone_connect.sync_state (scope = sistema S/4, entity = "PURCHASE_ORDER").
 * Si vede e si azzera con gli strumenti di eone-connect, come le altre sincronizzazioni.
 */
public final class SyncWatermark implements PurchaseOrderPoller.Watermark {

    public static final String ENTITY = "PURCHASE_ORDER";

    private final SyncState sync;
    private final String scope;
    private SyncState.Run run;

    public SyncWatermark(SyncState sync, String scope) {
        this.sync = sync;
        this.scope = scope;
    }

    @Override
    public Instant begin() {
        run = sync.begin(scope, ENTITY);
        String w = run.watermark();
        return w == null || w.isBlank() ? null : Instant.parse(w);
    }

    @Override
    public void advance(Instant to, long items, String message) {
        if (run == null) return;
        run.ok(to.toString(), items);
        run = null;
    }

    @Override
    public void failed(String message) {
        if (run == null) return;
        run.error(message);
        run = null;
    }
}
