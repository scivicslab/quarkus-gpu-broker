package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceCallException;
import com.scivicslab.gpubroker.llm.AiServiceClient;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * One concurrency slot against one physical {@code AiServiceEndpoint} — the
 * completion-driven, one-job-at-a-time pull loop. A single {@code
 * AiServiceEndpointWorker} still never runs two jobs at once (this is what
 * makes it safe to keep the blocking {@code client.send} call); {@code
 * AiServiceEndpoint} gets concurrency by running several of these workers
 * side by side, each its own POJO-actor with its own mailbox thread.
 *
 * <p>{@code JobQueue} tracks worker identities (e.g. {@code
 * 192.168.5.16:8000#0}), not the physical {@code AiServiceEndpoint}'s own
 * identity — see {@code 018_concurrency_control/000_PerEndpointConcurrency_260810_oo01}
 * for why this needs zero changes to {@code JobQueue} itself.
 *
 * <p>A single concrete class, not subclassed per {@code EndpointProbe} kind
 * — {@code requestPath} is the only thing that ever varied between the old
 * per-kind subclasses, and it is pure data (a String), not behavior. See
 * {@code AiServiceEndpointSubclasses_260810_oo01} "なぜサブクラスは EndpointProbe 側に
 * 作り、AiServiceEndpointWorker・Builder 側には作らないか".
 */
public final class AiServiceEndpointWorker {

    private static final int MAX_ATTEMPTS = 3;

    private final String queueName;
    private final String address;
    private final AiServiceClient client;
    private final String requestPath;
    private ActorSystem system;
    private ActorRef<AiServiceEndpointWorker> self;
    /** Where the outcome of each job is reported; null when nothing is keeping a record. */
    private ActorRef<com.scivicslab.gpubroker.history.StatusHistoryStore> history;

    public AiServiceEndpointWorker(String queueName, String address, AiServiceClient client, String requestPath) {
        this.queueName = queueName;
        this.address = address;
        this.client = client;
        this.requestPath = requestPath;
    }

    /** Bind this actor's own reference; must run before {@link #start}. */
    public void bind(ActorSystem system, ActorRef<AiServiceEndpointWorker> self) {
        this.system = system;
        this.self = self;
    }

    /**
     * @param history told whether each job ran, so liveness is measured by the work rather than by
     *                a probe standing in for it ({@code LivenessFromWorkNotOnlyProbes_260920_oo01});
     *                null to keep no record
     */
    public void setHistory(ActorRef<com.scivicslab.gpubroker.history.StatusHistoryStore> history) {
        this.history = history;
    }

    /** Enter rotation so the queue can hand this worker its first job. */
    public void start() {
        queue().tell(q -> {
            Job job = q.attach(self.getName());
            if (job != null) {
                self.tell(w -> w.assign(job));
            }
        });
    }

    /** Process one job to completion, then pull the next (completion-driven). */
    public void assign(Job job) {
        boolean succeeded = true;
        try {
            job.responseSink().servedBy(address);     // whatever measures this job now knows where it ran
            client.send(address, requestPath, job);   // this actor's own virtual thread waits for completion
        } catch (AiServiceCallException e) {
            succeeded = false;
            requeue(job);
        }
        reportOutcome(succeeded);
        boolean completed = succeeded;
        queue().tell(q -> {
            // Counted only on success: a failed call was handed to another endpoint by
            // requeue and has not finished yet — see StatusHistory_260905_oo01.
            if (completed) {
                q.recordCompleted();
            }
            Job next = q.requestWork(self.getName());
            if (next != null) {
                self.tell(w -> w.assign(next));
            }
        });
    }

    /**
     * Says whether this address did the work. A 5xx or a refused connection is what
     * {@code HttpAiServiceClient} raises on, so this is the same evidence the retry acts on --
     * until now it was used to move the job and then thrown away.
     */
    private void reportOutcome(boolean ok) {
        ActorRef<com.scivicslab.gpubroker.history.StatusHistoryStore> record = history;
        if (record == null) {
            return;
        }
        java.time.Instant now = java.time.Instant.now();
        record.tell(h -> h.recordWork(now, address, queueName, ok));
    }

    /** Hand this worker to another use; stop receiving work. */
    public void detach() {
        queue().tell(q -> q.withdraw(self.getName()));
    }

    private void requeue(Job job) {
        if (job.attempt() + 1 >= MAX_ATTEMPTS) {
            job.responseSink().fail(new AiServiceCallException(
                    "gave up after " + MAX_ATTEMPTS + " attempts, address=" + address));
            queue().tell(JobQueue::recordFailed);
            return;
        }
        Job next = job.nextAttempt();
        String endpointId = queue().ask(q -> q.submit(next)).join();
        if (endpointId != null) {
            wake(endpointId, next);
        }
    }

    private void wake(String endpointId, Job job) {
        ActorRef<AiServiceEndpointWorker> worker = system.getActor(endpointId);
        worker.tell(w -> w.assign(job));
    }

    private ActorRef<JobQueue> queue() {
        return system.getActor(queueName);
    }
}
