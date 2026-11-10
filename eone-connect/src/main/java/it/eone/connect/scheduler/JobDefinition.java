package it.eone.connect.scheduler;

import java.time.Instant;
import java.util.Map;

public record JobDefinition(String code, String jobKey, String description, String schedule, String zone,
                            boolean enabled, Map<String, String> params, Instant nextRunAt,
                            Instant lastIdleAt) {}
