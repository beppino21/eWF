package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.DesignerService.FunctionRow;
import it.eone.ewf.service.DesignerService.HookRow;
import it.eone.ewf.service.DesignerService.OptionRow;
import it.eone.ewf.service.DesignerService.TaskExtras;
import it.eone.ewf.service.DesignerService.TaskEdit;
import it.eone.ewf.service.DesignerService.TaskRow;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;

/**
 * Modifica di un task di una bozza: testo, chi lo esegue (regole per ruolo, richiedente, chi ha eseguito il passo
 * prima, assegnatario del passo prima, handler), team, peso, anteprima, interazione, avviso immediato.
 * Sotto, per un task gia' salvato: agganci (handler per evento), opzioni (nodo di scelta) e funzioni speciali.
 * "Salva" non chiude la finestra: si chiude con "Chiudi".
 */
@CCGenClass(expressionBase = "#{d.TaskEditUI}")
public class TaskEditUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        void saved();
        void cancelled();
    }

    private IListener m_listener;
    private long m_versionId;
    private long m_stepId;
    private Long m_taskId;

    private final FIXGRIDListBinding<HookItem> m_hooks = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<OptionItem> m_options = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<FunctionItem> m_functions = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_eventsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_actionsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_effectsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_typesVVB = new ValidValuesBinding();

    // aggancio in modifica (origEvent null = nuovo)
    private String h_origEvent;
    private Integer h_origSeq;
    private String h_event = "ON_OK";
    private String h_handler;
    private String h_label;
    private String h_config;
    // opzione in modifica (o_orig null = nuova)
    private String o_orig;
    private String o_code;
    private String o_label;
    private String o_effect = "FORWARD";
    private String o_process;
    private String o_handler;
    private String o_config;
    // funzione in modifica (f_id 0 = nuova)
    private long f_id;
    private String f_code;
    private String f_label;
    private String f_handler;
    private String f_confirm;

    private final ValidValuesBinding m_modesVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_rolesVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_teamsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_handlersVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_previewVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_interactionVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_notifyVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_commentVVB = new ValidValuesBinding();
    private String m_commentOk = "OPTIONAL";
    private String m_commentKo = "REQUIRED";

    private String m_text;
    private String m_mode = "RULES";
    private String m_role;
    private String m_handler;
    private boolean m_teamTask;
    private String m_team;
    private String m_weight = "0";
    private String m_preview = "NAME";
    private String m_interaction = "WEB";
    private String m_notify = "NONE";
    private String m_taskParam;

    public TaskEditUI() {
        SessionGuard.requireUser("/TaskEdit.xml");
        m_modesVVB.addValidValue("RULES", "Regole di assegnazione per ruolo");
        m_modesVVB.addValidValue("STARTER", "Richiedente (chi ha avviato)");
        m_modesVVB.addValidValue("ACTOR", "Chi ha eseguito il passo prima");
        m_modesVVB.addValidValue("PREVIOUS", "Assegnatario del passo prima");
        m_modesVVB.addValidValue("HANDLER", "Handler di assegnazione");
        m_previewVVB.addValidValue("NAME", "nome della persona");
        m_previewVVB.addValidValue("ROLE", "solo il ruolo");
        m_previewVVB.addValidValue("HIDDEN", "non mostrare");
        m_interactionVVB.addValidValue("WEB", "dal web");
        m_interactionVVB.addValidValue("INTERACTIVE", "decisione in SAP (My Inbox)");
        m_interactionVVB.addValidValue("SAPGUI_ONLY", "solo da SAP GUI");
        m_notifyVVB.addValidValue("NONE", "solo nel riepilogo periodico");
        m_notifyVVB.addValidValue("IMMEDIATE", "mail subito");
        m_commentVVB.addValidValue("NONE", "non richiesto");
        m_commentVVB.addValidValue("OPTIONAL", "facoltativo");
        m_commentVVB.addValidValue("REQUIRED", "obbligatorio");
        m_eventsVVB.addValidValue("ON_ACTIVATE", "all'arrivo del task");
        m_eventsVVB.addValidValue("BEFORE_ACTION", "prima dell'azione (puo' bloccarla)");
        m_eventsVVB.addValidValue("ON_OK", "su approvazione");
        m_eventsVVB.addValidValue("ON_KO", "su rifiuto");
        m_eventsVVB.addValidValue("BUTTON", "pulsante aggiuntivo");
        m_effectsVVB.addValidValue("FORWARD", "prosegue (come un'approvazione)");
        m_effectsVVB.addValidValue("REJECT", "respinge (come un rifiuto)");
        m_effectsVVB.addValidValue("HANDOFF", "passa a un altro processo (questo si chiude)");
        m_effectsVVB.addValidValue("PARALLEL", "avvia un altro processo in parallelo e prosegue");
    }

    @Override public String getPageName() { return "/TaskEdit.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.TaskEditUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static DesignerService designer() { return EwfApp.get().designer(); }

    /** t null = nuovo task nello step. */
    public void prepare(long versionId, long stepId, TaskRow t, IListener listener) {
        m_versionId = versionId;
        m_stepId = stepId;
        m_listener = listener;
        try {
            fill(m_rolesVVB, designer().roles(uid()));
            fill(m_teamsVVB, designer().teams(uid()));
            fill(m_handlersVVB, designer().handlers(uid(), "ASSIGNEE"));
            fill(m_actionsVVB, designer().handlers(uid(), "TASK_ACTION"));
            fill(m_typesVVB, designer().processTypes(uid()));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        if (t == null) return;
        m_taskId = t.id();
        m_text = t.text();
        m_mode = t.assigneeMode();
        m_role = t.roleCode();
        m_handler = t.handlerCode();
        m_teamTask = t.teamTask();
        m_team = t.teamCode();
        m_weight = String.valueOf(t.requiredWeight());
        m_preview = t.previewMode();
        m_interaction = t.interaction();
        m_notify = t.notifyMode();
        m_taskParam = t.taskParam();
        m_commentOk = t.commentOnApprove();
        m_commentKo = t.commentOnReject();
        loadExtras();
    }

    private void loadExtras() {
        m_hooks.getItems().clear();
        m_options.getItems().clear();
        m_functions.getItems().clear();
        if (m_taskId == null) return;
        try {
            TaskExtras x = designer().extras(uid(), m_versionId, m_taskId);
            for (HookRow h : x.hooks()) m_hooks.getItems().add(new HookItem(h));
            for (OptionRow o : x.options()) m_options.getItems().add(new OptionItem(o));
            for (FunctionRow f : x.functions()) m_functions.getItems().add(new FunctionItem(f));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private static void fill(ValidValuesBinding vvb, java.util.List<Choice> list) {
        vvb.clear();
        for (Choice c : list)
            vvb.addValidValue(c.code(), c.text() == null || c.text().equals(c.code()) ? c.code() : c.code() + " – " + c.text());
    }

    /** Descrizione breve di chi esegue il task (per le griglie). */
    static String whoText(TaskRow t) {
        String w = switch (t.assigneeMode()) {
            case "STARTER" -> "Richiedente";
            case "ACTOR" -> "Chi ha eseguito prima";
            case "PREVIOUS" -> "Assegnatario precedente";
            case "HANDLER" -> "Handler " + (t.handlerCode() == null ? "?" : t.handlerCode());
            default -> t.roleCode() == null ? "Regole" : t.roleCode();
        };
        if (t.teamCode() != null) w += " · team " + t.teamCode();
        else if (t.teamTask()) w += " · di team";
        return w;
    }

    // ------------------------------------------------------------------ azioni

    public void onSave(ActionEvent ae) {
        int weight;
        try {
            weight = m_weight == null || m_weight.isBlank() ? 0 : Integer.parseInt(m_weight.trim());
        } catch (NumberFormatException e) {
            Statusbar.outputError("Peso non valido");
            return;
        }
        try {
            boolean isNew = m_taskId == null;
            m_taskId = designer().saveTask(uid(), m_versionId, new TaskEdit(m_taskId, m_stepId, m_text, m_mode, m_role, m_handler,
                    m_teamTask, m_team, weight, m_preview, m_interaction, m_notify, m_taskParam, m_commentOk, m_commentKo));
            Statusbar.outputSuccess(isNew ? "Task creato: ora si possono aggiungere agganci, opzioni e funzioni" : "Task salvato");
            loadExtras();
            if (m_listener != null) m_listener.saved();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onCancel(ActionEvent ae) { if (m_listener != null) m_listener.cancelled(); }

    public void onModeChanged(ActionEvent ae) { }

    public void onNoop(ActionEvent ae) { }

    private void changed() {
        loadExtras();
        if (m_listener != null) m_listener.saved();
    }

    // --- agganci
    public void onSaveHook(ActionEvent ae) {
        try {
            designer().saveHook(uid(), m_versionId, m_taskId, h_origEvent, h_origSeq, h_event, h_handler, h_label, h_config);
            onNewHook(null);
            changed();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onNewHook(ActionEvent ae) {
        h_origEvent = null;
        h_origSeq = null;
        h_event = "ON_OK";
        h_handler = null;
        h_label = null;
        h_config = null;
    }

    // --- opzioni
    public void onSaveOption(ActionEvent ae) {
        boolean spawn = "HANDOFF".equals(o_effect) || "PARALLEL".equals(o_effect);
        try {
            designer().saveOption(uid(), m_versionId, m_taskId, o_orig, new OptionRow(o_code, 0, o_label,
                    "REJECT".equals(o_effect), o_handler, o_config, spawn ? o_process : null, spawn ? o_effect : null));
            onNewOption(null);
            changed();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onNewOption(ActionEvent ae) {
        o_orig = null;
        o_code = null;
        o_label = null;
        o_effect = "FORWARD";
        o_process = null;
        o_handler = null;
        o_config = null;
    }

    // --- funzioni
    public void onSaveFunction(ActionEvent ae) {
        try {
            designer().saveFunction(uid(), m_versionId, m_taskId, new FunctionRow(f_id, f_code, f_label, f_handler, f_confirm, 0));
            onNewFunction(null);
            changed();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onNewFunction(ActionEvent ae) {
        f_id = 0;
        f_code = null;
        f_label = null;
        f_handler = null;
        f_confirm = null;
    }

    private void confirmDelete(String title, String text, Runnable action) {
        YESNOPopup.createInstance(title, text, new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    action.run();
                    changed();
                } catch (ServiceException e) {
                    Statusbar.outputError(e.getMessage());
                }
            }
            @Override public void reactOnNo() { }
        });
    }

    // ------------------------------------------------------------------ binding

    public ValidValuesBinding getModesVVB() { return m_modesVVB; }
    public ValidValuesBinding getRolesVVB() { return m_rolesVVB; }
    public ValidValuesBinding getTeamsVVB() { return m_teamsVVB; }
    public ValidValuesBinding getHandlersVVB() { return m_handlersVVB; }
    public ValidValuesBinding getPreviewVVB() { return m_previewVVB; }
    public ValidValuesBinding getInteractionVVB() { return m_interactionVVB; }
    public ValidValuesBinding getNotifyVVB() { return m_notifyVVB; }
    public ValidValuesBinding getCommentVVB() { return m_commentVVB; }
    public String getCommentOk() { return m_commentOk; }
    public void setCommentOk(String v) { m_commentOk = v == null ? "OPTIONAL" : v; }
    public String getCommentKo() { return m_commentKo; }
    public void setCommentKo(String v) { m_commentKo = v == null ? "REQUIRED" : v; }
    public boolean isRulesMode() { return "RULES".equals(m_mode); }
    public boolean isHandlerMode() { return "HANDLER".equals(m_mode); }
    public boolean isExisting() { return m_taskId != null; }
    public boolean isNotExisting() { return m_taskId == null; }
    public FIXGRIDListBinding<HookItem> getHooks() { return m_hooks; }
    public FIXGRIDListBinding<OptionItem> getOptions() { return m_options; }
    public FIXGRIDListBinding<FunctionItem> getFunctions() { return m_functions; }
    public ValidValuesBinding getEventsVVB() { return m_eventsVVB; }
    public ValidValuesBinding getActionsVVB() { return m_actionsVVB; }
    public ValidValuesBinding getEffectsVVB() { return m_effectsVVB; }
    public ValidValuesBinding getTypesVVB() { return m_typesVVB; }
    public String getHooksTitle() { return "Agganci (" + m_hooks.getItems().size() + ")"; }
    public String getOptionsTitle() {
        return "Opzioni del nodo di scelta (" + m_options.getItems().size() + ")"
                + (m_options.getItems().isEmpty() ? " \u2013 senza opzioni il task ha Approva / Respingi" : "");
    }
    public String getFunctionsTitle() { return "Funzioni speciali (" + m_functions.getItems().size() + ")"; }
    public String getHookFormTitle() { return h_origEvent == null ? "Nuovo aggancio" : "Modifica aggancio"; }
    public String getOptionFormTitle() { return o_orig == null ? "Nuova opzione" : "Modifica opzione " + o_orig; }
    public String getFunctionFormTitle() { return f_id == 0 ? "Nuova funzione" : "Modifica funzione"; }
    public boolean isButtonHook() { return "BUTTON".equals(h_event); }
    public boolean isSpawnOption() { return "HANDOFF".equals(o_effect) || "PARALLEL".equals(o_effect); }

    public String getHookEvent() { return h_event; }
    public void setHookEvent(String v) { h_event = v; }
    public String getHookHandler() { return h_handler; }
    public void setHookHandler(String v) { h_handler = blankToNull(v); }
    public String getHookLabel() { return h_label; }
    public void setHookLabel(String v) { h_label = v; }
    public String getHookConfig() { return h_config; }
    public void setHookConfig(String v) { h_config = v; }
    public String getOptCode() { return o_code; }
    public void setOptCode(String v) { o_code = v; }
    public String getOptLabel() { return o_label; }
    public void setOptLabel(String v) { o_label = v; }
    public String getOptEffect() { return o_effect; }
    public void setOptEffect(String v) { o_effect = v == null ? "FORWARD" : v; }
    public String getOptProcess() { return o_process; }
    public void setOptProcess(String v) { o_process = blankToNull(v); }
    public String getOptHandler() { return o_handler; }
    public void setOptHandler(String v) { o_handler = blankToNull(v); }
    public String getOptConfig() { return o_config; }
    public void setOptConfig(String v) { o_config = v; }
    public String getFnCode() { return f_code; }
    public void setFnCode(String v) { f_code = v; }
    public String getFnLabel() { return f_label; }
    public void setFnLabel(String v) { f_label = v; }
    public String getFnHandler() { return f_handler; }
    public void setFnHandler(String v) { f_handler = blankToNull(v); }
    public String getFnConfirm() { return f_confirm; }
    public void setFnConfirm(String v) { f_confirm = v; }

    public String getText() { return m_text; }
    public void setText(String v) { m_text = v; }
    public String getMode() { return m_mode; }
    public void setMode(String v) { m_mode = v == null ? "RULES" : v; }
    public String getRole() { return m_role; }
    public void setRole(String v) { m_role = blankToNull(v); }
    public String getHandler() { return m_handler; }
    public void setHandler(String v) { m_handler = blankToNull(v); }
    public boolean isTeamTask() { return m_teamTask; }
    public void setTeamTask(boolean v) { m_teamTask = v; }
    public String getTeam() { return m_team; }
    public void setTeam(String v) { m_team = blankToNull(v); }
    public String getWeight() { return m_weight; }
    public void setWeight(String v) { m_weight = v; }
    public String getPreview() { return m_preview; }
    public void setPreview(String v) { m_preview = v == null ? "NAME" : v; }
    public String getInteraction() { return m_interaction; }
    public void setInteraction(String v) { m_interaction = v == null ? "WEB" : v; }
    public String getNotify() { return m_notify; }
    public void setNotify(String v) { m_notify = v == null ? "NONE" : v; }
    public String getTaskParam() { return m_taskParam; }
    public void setTaskParam(String v) { m_taskParam = blankToNull(v); }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v; }

    // ------------------------------------------------------------------ righe

    static String eventText(String e) {
        return switch (e) {
            case "ON_ACTIVATE" -> "all'arrivo";
            case "BEFORE_ACTION" -> "prima dell'azione";
            case "ON_OK" -> "su approvazione";
            case "ON_KO" -> "su rifiuto";
            case "BUTTON" -> "pulsante";
            default -> e;
        };
    }

    public class HookItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final HookRow h;
        HookItem(HookRow h) { this.h = h; }
        public String getEvent() { return eventText(h.event()); }
        public String getHandler() { return h.handlerCode(); }
        public String getDetail() {
            String l = h.label() == null ? "" : "\u201c" + h.label() + "\u201d ";
            return l + h.config();
        }
        public void onEdit(ActionEvent ae) {
            h_origEvent = h.event();
            h_origSeq = h.seq();
            h_event = h.event();
            h_handler = h.handlerCode();
            h_label = h.label();
            h_config = h.config();
        }
        public void onDelete(ActionEvent ae) {
            confirmDelete("Elimina aggancio", "Togliere " + h.handlerCode() + " (" + eventText(h.event()) + ")?",
                    () -> designer().deleteHook(uid(), m_versionId, m_taskId, h.event(), h.seq()));
        }
    }

    public class OptionItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final OptionRow o;
        OptionItem(OptionRow o) { this.o = o; }
        public String getCode() { return o.code(); }
        public String getLabel() { return o.label(); }
        public String getEffect() {
            if (o.reject()) return "respinge";
            if ("HANDOFF".equals(o.spawnMode())) return "passa a " + o.startProcess();
            if ("PARALLEL".equals(o.spawnMode())) return "avvia " + o.startProcess() + " e prosegue";
            return "prosegue";
        }
        public String getEffectColor() { return o.reject() ? "#C62828" : o.spawnMode() != null ? "#6A1B9A" : "#2E7D32"; }
        public String getHandler() { return o.handlerCode() == null ? "" : o.handlerCode(); }
        public void onEdit(ActionEvent ae) {
            o_orig = o.code();
            o_code = o.code();
            o_label = o.label();
            o_effect = o.reject() ? "REJECT" : o.spawnMode() != null ? o.spawnMode() : "FORWARD";
            o_process = o.startProcess();
            o_handler = o.handlerCode();
            o_config = o.config();
        }
        public void onDelete(ActionEvent ae) {
            confirmDelete("Elimina opzione", "Togliere l'opzione " + o.code() + " \u2013 " + o.label() + "?",
                    () -> designer().deleteOption(uid(), m_versionId, m_taskId, o.code()));
        }
    }

    public class FunctionItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final FunctionRow f;
        FunctionItem(FunctionRow f) { this.f = f; }
        public String getCode() { return f.code(); }
        public String getLabel() { return f.label(); }
        public String getHandler() { return f.handlerCode(); }
        public String getConfirm() { return f.confirmText() == null ? "" : f.confirmText(); }
        public void onEdit(ActionEvent ae) {
            f_id = f.id();
            f_code = f.code();
            f_label = f.label();
            f_handler = f.handlerCode();
            f_confirm = f.confirmText();
        }
        public void onDelete(ActionEvent ae) {
            confirmDelete("Elimina funzione", "Togliere la funzione \u201c" + f.label() + "\u201d?",
                    () -> designer().deleteFunction(uid(), m_versionId, m_taskId, f.id()));
        }
    }
}
