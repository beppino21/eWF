package it.eone.connect.scheduler;

import java.util.Map;
import java.util.function.BooleanSupplier;

public record JobContext(String jobCode, long runId, String node, Map<String, String> params, BooleanSupplier stop) {
    public boolean stopRequested() { return stop.getAsBoolean(); }
    public String param(String name, String def) { return params.getOrDefault(name, def); }
}
