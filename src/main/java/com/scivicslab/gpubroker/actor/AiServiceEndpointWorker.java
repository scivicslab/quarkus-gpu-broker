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
 */
public abstract class AiServiceEndpointWorker {

    private static final int MAX_ATTEMPTS = 3;

    private final String queueName;
    private final String address;
    private final AiServiceClient client;
    private ActorSystem system;
    private ActorRef<AiServiceEndpointWorker> self;

    protected AiServiceEndpointWorker(String queueName, String address, AiServiceClient client) {
        this.queueName = queueName;
        this.address = address;
        this.client = client;
    }

    /** The URL path this worker's real requests go to (e.g. {@code /v1/chat/completions}). */
    protected abstract String requestPath();

    /** Bind this actor's own reference; must run before {@link #start}. */
    public void bind(ActorSystem system, ActorRef<AiServiceEndpointWorker> self) {
        this.system = system;
        this.self = self;
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
        try {
            client.send(address, requestPath(), job);   // this actor's own virtual thread waits for completion
        } catch (AiServiceCallException e) {
            requeue(job);
        }
        queue().tell(q -> {
            Job next = q.requestWork(self.getName());
            if (next != null) {
                self.tell(w -> w.assign(next));
            }
        });
    }

    /** Hand this worker to another use; stop receiving work. */
    public void detach() {
        queue().tell(q -> q.withdraw(self.getName()));
    }

    private void requeue(Job job) {
        if (job.attempt() + 1 >= MAX_ATTEMPTS) {
            job.responseSink().fail(new AiServiceCallException(
                    "gave up after " + MAX_ATTEMPTS + " attempts, address=" + address));
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
