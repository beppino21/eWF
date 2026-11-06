package it.eone.ewf.engine.model;

import java.util.Map;

/** Aggancio di un handler a un evento del task, con i suoi parametri. */
public record HookDef(HookEvent event, int seq, String handlerCode, Map<String, String> config, String label) {
    public HookDef {
        config = config == null ? Map.of() : Map.copyOf(config);
    }
    public static HookDef of(HookEvent event, String handlerCode) {
        return new HookDef(event, 1, handlerCode, Map.of(), null);
    }
    public static HookDef of(HookEvent event, String handlerCode, Map<String, String> config) {
        return new HookDef(event, 1, handlerCode, config, null);
    }
}
