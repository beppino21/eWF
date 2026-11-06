package it.eone.ewf.sap;

import it.eone.ewf.engine.model.HookEvent;
import it.eone.ewf.engine.spi.Contract;
import it.eone.ewf.engine.spi.HandlerContext;
import it.eone.ewf.engine.spi.HandlerContext.Actor;
import it.eone.ewf.engine.spi.HandlerContext.ProcessRef;
import it.eone.ewf.engine.spi.HandlerContext.TaskRef;
import it.eone.ewf.engine.spi.HandlerResult;

import java.util.Map;
import java.util.UUID;

/**
 * Prova a riga di comando del collegamento Java -> /EWF/ENGINE_DISPATCH -> FM.
 * Esegue due volte la stessa chiamata con la stessa chiave: la seconda deve risultare "replayed".
 *
 * Argomenti: &lt;destinazione&gt; &lt;FM handler&gt; &lt;documento&gt; [tipo doc, default F] [CHIAVE=VALORE ...]
 * Esempio:   EWF_DEV /EWF/WF_CHECK_LOCK_EKKO 4500001234 F
 */
public final class DispatcherProbe {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Uso: DispatcherProbe <destinazione> <FM handler> <documento> [tipo doc] [CHIAVE=VALORE ...]");
            return;
        }
        SapDestinations.register();
        String docType = args.length > 3 && !args[3].contains("=") ? args[3] : "F";
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (int i = 3; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq > 0) params.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }

        JcoDispatcherHandler h = new JcoDispatcherHandler(args[0], args[1]);
        HandlerContext ctx = new HandlerContext(UUID.randomUUID(), Contract.TASK_ACTION, HookEvent.ON_OK, "OK",
                new ProcessRef(9_000_000_001L, "PROBE", docType, args[2], null, UUID.randomUUID(), null),
                new TaskRef(9_000_000_001L, 10, 0, "PROBE", null),
                new Actor(null, "PROBE", null, false, false), params);

        System.out.println("Chiave di idempotenza: " + ctx.idempotencyKey());
        print("1a chiamata", h.execute(ctx));
        print("2a chiamata (stessa chiave)", h.execute(ctx));
    }

    private static void print(String label, HandlerResult r) {
        System.out.println("--- " + label);
        System.out.println("esito:      " + r.status() + (r.replayed() ? "  (dal registro, non rieseguita)" : ""));
        System.out.println("messaggio:  " + r.message());
        if (!r.documents().isEmpty()) System.out.println("documenti:  " + r.documents());
        if (!r.users().isEmpty()) System.out.println("utenti:     " + r.users());
        if (!r.directives().isEmpty()) System.out.println("istruzioni: " + r.directives());
        if (!r.output().isEmpty()) System.out.println("altro:      " + r.output());
    }
}
