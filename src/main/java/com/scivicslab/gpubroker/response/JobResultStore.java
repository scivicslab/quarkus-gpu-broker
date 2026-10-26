package com.scivicslab.gpubroker.response;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * Holds {@code jobId} to {@link JobResult} for the non-streaming (submit-
 * then-poll) path. With a {@link JobJournal} it survives a broker restart: every accepted job and
 * every result is also written to disk, and a new instance reads them back -- a finished job
 * answers {@code GET} as before, a job that had not finished is listed by {@link #recoverable} for
 * the caller to run again. Without a journal it is in-memory only. See
 * {@code JobResultStoreLifecycle_260810_oo01}.
 *
 * <p>{@link #sweep} runs on a schedule rather than checking expiry on {@link
 * #get}, because an entry nobody ever polls would otherwise never be
 * checked at all — which is exactly the unbounded-growth problem this class
 * exists to prevent.
 *
 * <p>A plain POJO, run as an actor ({@code JobResultStoreProducer} wraps it
 * in the one {@code ActorRef} every caller shares) — its mailbox is what
 * makes {@link #results} safe to mutate from {@link #register}, {@link
 * #complete}, {@link #fail}, and the scheduled {@link #sweep} without a
 * concurrent map of its own, the same reason {@code JobQueue} needs none.</p>
 */
public class JobResultStore {

    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final Map<String, JobResult> results = new HashMap<>();
    /** Jobs read back from disk that had not finished, until the caller has run them again. */
    private final Map<String, JobJournal.Accepted> recoverable = new HashMap<>();
    private final Duration ttl;
    private final JobJournal journal;

    public JobResultStore() {
        this(DEFAULT_TTL);
    }

    /** Package-visible: lets tests use a short TTL instead of waiting a real hour. */
    JobResultStore(Duration ttl) {
        this(ttl, null);
    }

    /** Keeps the jobs in {@code journal} and reads back the ones it already holds. */
    public JobResultStore(JobJournal journal) {
        this(DEFAULT_TTL, journal);
    }

    JobResultStore(Duration ttl, JobJournal journal) {
        this.ttl = ttl;
        this.journal = journal;
        if (journal != null) {
            restore();
        }
    }

    private void restore() {
        Instant cutoff = Instant.now().minus(ttl);
        for (JobJournal.Entry entry : journal.loadAll()) {
            Instant created = entry.result().map(JobResult::createdAt)
                    .orElseGet(() -> entry.accepted().get().createdAt());
            if (created.isBefore(cutoff)) {
                journal.delete(entry.jobId());
            } else if (entry.result().isPresent()) {
                results.put(entry.jobId(), entry.result().get());
            } else {
                JobJournal.Accepted accepted = entry.accepted().get();
                results.put(entry.jobId(), new JobResult(JobResult.Status.PENDING, null, null, null, created));
                recoverable.put(entry.jobId(), accepted);
            }
        }
    }

    public String register() {
        String jobId = UUID.randomUUID().toString();
        results.put(jobId, JobResult.pending());
        return jobId;
    }

    /** Registers a job that arrived at {@code queueName}, keeping what is needed to run it again. */
    public String register(String queueName, Priority priority, String submitterId, RequestBody request) {
        String jobId = register();
        if (journal != null) {
            journal.recordAccepted(new JobJournal.Accepted(jobId, queueName, priority, submitterId,
                    request.contentType(), request.bytes(), results.get(jobId).createdAt()));
        }
        return jobId;
    }

    public void complete(String jobId, byte[] body, String contentType) {
        JobResult ended = results.computeIfPresent(jobId, (id, existing) -> existing.done(body, contentType));
        record(jobId, ended);
    }

    public void fail(String jobId, Throwable cause) {
        JobResult ended = results.computeIfPresent(jobId, (id, existing) -> existing.failed(cause));
        record(jobId, ended);
    }

    private void record(String jobId, JobResult ended) {
        recoverable.remove(jobId);
        if (journal != null && ended != null) {
            journal.recordResult(jobId, ended);
        }
    }

    /** The jobs read back from disk that no one has run again yet. */
    public List<JobJournal.Accepted> recoverable() {
        return new ArrayList<>(recoverable.values());
    }

    /** The caller has put this job back on its queue; it is no longer listed by {@link #recoverable}. */
    public void resubmitted(String jobId) {
        recoverable.remove(jobId);
    }

    public Optional<JobResult> get(String jobId) {
        return Optional.ofNullable(results.get(jobId));
    }

    /** Called on a schedule by {@code JobResultSweepScheduler}, via this actor's own mailbox. */
    void sweep() {
        Instant cutoff = Instant.now().minus(ttl);
        results.entrySet().removeIf(e -> {
            boolean expired = e.getValue().createdAt().isBefore(cutoff);
            if (expired) {
                recoverable.remove(e.getKey());
                if (journal != null) {
                    journal.delete(e.getKey());
                }
            }
            return expired;
        });
    }
}
