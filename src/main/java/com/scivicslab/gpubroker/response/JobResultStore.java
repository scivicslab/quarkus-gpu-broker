package com.scivicslab.gpubroker.response;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Singleton;

/**
 * Holds {@code jobId} to {@link JobResult} for the non-streaming (submit-
 * then-poll) path. In-memory only: an entry not yet {@code GET} is lost on
 * broker restart regardless (graceful drain or not), so persisting it would
 * not actually help — see {@code JobResultStoreLifecycle_260810_oo01}
 * "なぜ再起動をまたぐ永続化を設けないか".
 *
 * <p>{@link #sweep} runs on a schedule rather than checking expiry on {@link
 * #get}, because an entry nobody ever polls would otherwise never be
 * checked at all — which is exactly the unbounded-growth problem this class
 * exists to prevent.
 */
@Singleton
public class JobResultStore {

    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final Map<String, JobResult> results = new ConcurrentHashMap<>();
    private final Duration ttl;

    public JobResultStore() {
        this(DEFAULT_TTL);
    }

    /** Package-visible: lets tests use a short TTL instead of waiting a real hour. */
    JobResultStore(Duration ttl) {
        this.ttl = ttl;
    }

    public String register() {
        String jobId = UUID.randomUUID().toString();
        results.put(jobId, JobResult.pending());
        return jobId;
    }

    public void complete(String jobId, byte[] body, String contentType) {
        results.computeIfPresent(jobId, (id, existing) -> existing.done(body, contentType));
    }

    public void fail(String jobId, Throwable cause) {
        results.computeIfPresent(jobId, (id, existing) -> existing.failed(cause));
    }

    public Optional<JobResult> get(String jobId) {
        return Optional.ofNullable(results.get(jobId));
    }

    @Scheduled(every = "10m")
    void sweep() {
        Instant cutoff = Instant.now().minus(ttl);
        results.values().removeIf(r -> r.createdAt().isBefore(cutoff));
    }
}
