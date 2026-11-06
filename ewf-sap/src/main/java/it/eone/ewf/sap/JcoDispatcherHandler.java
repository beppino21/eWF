package it.eone.ewf.sap;

import com.sap.conn.jco.*;
import it.eone.ewf.engine.spi.Handler;
import it.eone.ewf.engine.spi.HandlerContext;
import it.eone.ewf.engine.spi.HandlerResult;
import it.eone.ewf.engine.spi.HandlerResult.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler che esegue una FM ABAP attraverso /EWF/ENGINE_DISPATCH.
 * Il codice handler e' il nome della FM (es. /EWF/WF_RIL_ODA).
 *
 * I_TASKRUN viene riempito dal contesto, cosi' le FM legacy funzionano senza modifiche di firma.
 * Parametri dell'aggancio (task_hook.config):
 *  - chiave = nome di un campo di /EWF/TASKRUN (es. Z_WF_SET_RIL) -> valorizza quel campo;
 *  - altre chiavi -> IT_PARAMS (es. I_AUART -> parametro omonimo della FM).
 *
 * Esiti: O -> OK, K -> KO, U -> UNKNOWN. Errori di comunicazione o di sistema durante la chiamata
 * -> UNKNOWN (l'azione potrebbe essere avvenuta); errori prima di raggiungere SAP -> KO.
 */
public final class JcoDispatcherHandler implements Handler {

    public static final String DISPATCHER = "/EWF/ENGINE_DISPATCH";

    private final String destinationName;
    private final String functionName;

    public JcoDispatcherHandler(String destinationName, String functionName) {
        this.destinationName = destinationName;
        this.functionName = functionName;
    }

    @Override
    public HandlerResult execute(HandlerContext ctx) {
        JCoDestination dest;
        JCoFunction f;
        try {
            dest = JCoDestinationManager.getDestination(destinationName);
            f = dest.getRepository().getFunction(DISPATCHER);
            if (f == null) return HandlerResult.ko(DISPATCHER + " non trovata nel sistema " + destinationName);
        } catch (JCoException e) {
            // non abbiamo ancora chiamato nulla: e' sicuro dire KO
            return HandlerResult.ko("SAP non raggiungibile (" + destinationName + "): " + e.getMessage());
        }

        JCoParameterList in = f.getImportParameterList();
        in.setValue("I_IDEMP_KEY", ctx.idempotencyKey().toString());
        in.setValue("I_HANDLER", functionName);
        in.setValue("I_CONTRACT", ctx.contract().name());
        in.setValue("I_EVENT", ctx.event() != null ? ctx.event().name() : ctx.contract().name());
        if (ctx.action() != null) in.setValue("I_ACTION", ctx.action());
        in.setValue("I_ACTOR", ctx.actor().login());
        if (ctx.actor().sapUser() != null) in.setValue("I_ACTOR_SAP", ctx.actor().sapUser());

        JCoStructure tr = in.getStructure("I_TASKRUN");
        JCoTable params = f.getTableParameterList().getTable("IT_PARAMS");
        fillTaskrun(tr, ctx);
        for (Map.Entry<String, String> e : ctx.params().entrySet()) {
            if (tr.getMetaData().hasField(e.getKey())) {
                tr.setValue(e.getKey(), e.getValue());
            } else {
                params.appendRow();
                params.setValue("NAME", e.getKey());
                params.setValue("VALUE", e.getValue());
            }
        }

        try {
            f.execute(dest);
        } catch (JCoException e) {
            // la richiesta e' partita: non sappiamo se l'handler ha agito
            return HandlerResult.unknown("Errore durante la chiamata a " + functionName + ": " + e.getMessage());
        }

        JCoParameterList out = f.getExportParameterList();
        String status = out.getString("E_STATUS");
        String message = blankToNull(out.getString("E_MESSAGE"));
        boolean replayed = "X".equals(out.getString("E_REPLAYED"));

        List<DocRef> docs = new ArrayList<>();
        JCoTable td = f.getTableParameterList().getTable("ET_DOCS");
        for (int i = 0; i < td.getNumRows(); i++) {
            td.setRow(i);
            docs.add(new DocRef(blankToNull(td.getString("DOC_CAT")), td.getString("DOC_NUMBER"),
                    blankToNull(td.getString("DOC_YEAR")), blankToNull(td.getString("COMP_CODE"))));
        }
        List<UserRef> users = new ArrayList<>();
        JCoTable tu = f.getTableParameterList().getTable("ET_USERS");
        for (int i = 0; i < tu.getNumRows(); i++) {
            tu.setRow(i);
            UserRef.IdType type = "SAP".equals(tu.getString("ID_TYPE")) ? UserRef.IdType.SAP : UserRef.IdType.EWF;
            users.add(new UserRef(type, tu.getString("USER_ID")));
        }
        List<Directive> directives = new ArrayList<>();
        Map<String, String> output = new LinkedHashMap<>();
        JCoTable to = f.getTableParameterList().getTable("ET_OUT");
        for (int i = 0; i < to.getNumRows(); i++) {
            to.setRow(i);
            String name = to.getString("NAME");
            String value = to.getString("VALUE");
            switch (name) {
                case "SKIP_TASK" -> directives.add(new SkipTask());
                case "SKIP_STEP" -> directives.add(new SkipStep(Integer.parseInt(value.trim())));
                default -> output.put(name, value);
            }
        }

        HandlerResult.Status st = switch (status == null ? "" : status) {
            case "O" -> HandlerResult.Status.OK;
            case "K" -> HandlerResult.Status.KO;
            default -> HandlerResult.Status.UNKNOWN;
        };
        return new HandlerResult(st, message, docs, users, directives, output, replayed);
    }

    /** Campi di /EWF/TASKRUN letti dalle FM legacy. */
    private static void fillTaskrun(JCoStructure tr, HandlerContext ctx) {
        HandlerContext.ProcessRef p = ctx.process();
        set(tr, "Z_WF_PROCID", String.valueOf(p.id()));
        set(tr, "Z_WF_PROCESSO", p.processType());
        set(tr, "Z_WF_TIPODOC", p.docType());
        set(tr, "Z_WF_DOCUMENT", p.documentKey());
        if (p.value() != null) set(tr, "Z_WF_WAERS", p.value().currency());
        if (ctx.task() != null) {
            set(tr, "Z_WF_TASKID", String.valueOf(ctx.task().id()));
            set(tr, "Z_WF_STEP", String.valueOf(ctx.task().stepNo()));
            set(tr, "Z_WF_SUBOP", String.valueOf(ctx.task().subopNo()));
            set(tr, "Z_WF_RUOLO", ctx.task().role());
            set(tr, "ZWF_TASK_PARAM", ctx.task().taskParam());
        }
        if (ctx.action() != null) set(tr, "Z_WF_AZIONE", ctx.action());
        if (!ctx.actor().system()) set(tr, "Z_WF_UTENTE", ctx.actor().login());
    }

    private static void set(JCoStructure tr, String field, String value) {
        if (value != null && tr.getMetaData().hasField(field)) tr.setValue(field, value);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
