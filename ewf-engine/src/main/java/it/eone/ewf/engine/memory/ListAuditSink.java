package it.eone.ewf.engine.memory;

import it.eone.ewf.engine.spi.AuditSink;

import java.util.ArrayList;
import java.util.List;

public final class ListAuditSink implements AuditSink {
    private final List<AuditEvent> events = new ArrayList<>();
    @Override public synchronized void append(AuditEvent e) { events.add(e); }
    public synchronized List<AuditEvent> events() { return List.copyOf(events); }
    public synchronized List<String> types(long processId) {
        return events.stream().filter(e -> e.processId() != null && e.processId() == processId)
                .map(AuditEvent::type).toList();
    }
}
