package it.eone.connect.scheduler;

import java.time.Instant;
import java.util.Map;

/** Una riga dello storico. status: RUNNING, OK, WARNING, ERROR, ABORTED. */
public record JobRun(long id, String jobCode, String node, Instant startedAt, Instant endedAt, String status,
                     String message, Map<String, Long> counts) {}
