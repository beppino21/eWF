package it.eone.connect.scheduler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Implementazioni dei job che l'applicazione mette a disposizione, per chiave (es. "ewf.start-queue").
 * Nel DB si salva la chiave, mai un nome di classe: niente reflection, niente classi sconosciute.
 */
public final class JobRegistry {
    private final Map<String, Supplier<Job>> jobs = new ConcurrentHashMap<>();

    public JobRegistry register(String key, Supplier<Job> factory) {
        jobs.put(key, factory);
        return this;
    }

    public JobRegistry register(String key, Job job) { return register(key, () -> job); }

    public Optional<Job> create(String key) {
        return Optional.ofNullable(jobs.get(key)).map(Supplier::get);
    }
}
