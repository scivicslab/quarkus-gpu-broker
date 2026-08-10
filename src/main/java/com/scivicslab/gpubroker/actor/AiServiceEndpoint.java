package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceCallException;
import com.scivicslab.gpubroker.llm.AiServiceClient;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * One physical AiServiceEndpoint (one host:port serving one AI capability),
 * bound to the {@code JobQueue} named {@link #queueName} — held as a name,
 * not an {@code ActorRef}, because {@code createChild} already tracks the
 * parent-child relationship and duplicating it in a field would be the same
 * mistake {@code ActorSuffixAndOwnedActorRef} warns against.
 *
 * <p>Abstract because the one thing that genuinely varies per {@code
 * EndpointKind} is behavior, not data: which URL path a real request goes
 * to. Each concrete subclass supplies {@link #requestPath()}; everything
 * else (bind/start/assign/detach, the completion-driven pull loop) is
 * shared here.
 */
public abstract class AiServiceEndpoint {

    private static final int MAX_ATTEMPTS = 3;

    private final String queueName;
    private final String address;
    private final AiServiceClient client;
    private ActorSystem system;
    private ActorRef<AiServiceEndpoint> self;

    protected AiServiceEndpoint(String queueName, String address, AiServiceClient client) {
        this.queueName = queueName;
        this.address = address;
        this.client = client;
    }

    /** The URL path this AiServiceEndpoint's real requests go to (e.g. {@code /v1/chat/completions}). */
    protected abstract String requestPath();

    /** Bind this actor's own reference; must run before {@link #start}. */
    public void bind(ActorSystem system, ActorRef<AiServiceEndpoint> self) {
        this.system = system;
        this.self = self;
    }

    /** Enter rotation so the queue can hand this endpoint its first job. */
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

    /** Hand this AiServiceEndpoint to another use; stop receiving work. */
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
        ActorRef<AiServiceEndpoint> endpoint = system.getActor(endpointId);
        endpoint.tell(w -> w.assign(job));
    }

    private ActorRef<JobQueue> queue() {
        return system.getActor(queueName);
    }
}
