package it.eone.ewf.engine.model;

import java.util.Comparator;
import java.util.List;

/** Task di uno step (ex riga di /EWF/TASK con il suo SUBOP). */
public record TaskDef(
        long id,
        int subopNo,
        String text,
        String roleCode,
        int requiredWeight,
        AssigneeMode assigneeMode,
        String assigneeHandlerCode,
        boolean teamTask,
        String teamCode,
        PreviewMode previewMode,
        Interaction interaction,
        String taskParam,
        List<HookDef> hooks,
        List<OptionDef> options) {

    public TaskDef {
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        options = options == null ? List.of() : List.copyOf(options);
        if (assigneeMode == null) assigneeMode = AssigneeMode.RULES;
        if (previewMode == null) previewMode = PreviewMode.NAME;
        if (interaction == null) interaction = Interaction.WEB;
    }

    /** Task senza opzioni (Approva / Respingi). */
    public TaskDef(long id, int subopNo, String text, String roleCode, int requiredWeight, AssigneeMode assigneeMode,
                   String assigneeHandlerCode, boolean teamTask, String teamCode, PreviewMode previewMode,
                   Interaction interaction, String taskParam, List<HookDef> hooks) {
        this(id, subopNo, text, roleCode, requiredWeight, assigneeMode, assigneeHandlerCode, teamTask, teamCode,
                previewMode, interaction, taskParam, hooks, List.of());
    }

    /** Nodo di scelta: al posto di Approva/Respingi l'utente deve scegliere un'opzione. */
    public boolean isChoice() { return !options.isEmpty(); }

    public java.util.Optional<OptionDef> option(String code) {
        return options.stream().filter(o -> o.code().equals(code)).findFirst();
    }

    public TaskDef withOptions(List<OptionDef> opts) {
        return new TaskDef(id, subopNo, text, roleCode, requiredWeight, assigneeMode, assigneeHandlerCode, teamTask,
                teamCode, previewMode, interaction, taskParam, hooks, opts);
    }

    public List<HookDef> hooks(HookEvent event) {
        return hooks.stream().filter(h -> h.event() == event)
                .sorted(Comparator.comparingInt(HookDef::seq)).toList();
    }
}
