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
        List<HookDef> hooks) {

    public TaskDef {
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        if (assigneeMode == null) assigneeMode = AssigneeMode.RULES;
        if (previewMode == null) previewMode = PreviewMode.NAME;
        if (interaction == null) interaction = Interaction.WEB;
    }

    public List<HookDef> hooks(HookEvent event) {
        return hooks.stream().filter(h -> h.event() == event)
                .sorted(Comparator.comparingInt(HookDef::seq)).toList();
    }
}
