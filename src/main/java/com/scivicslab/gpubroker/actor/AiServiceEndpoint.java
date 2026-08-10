package com.scivicslab.gpubroker.actor;

import java.util.function.Supplier;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * One physical AiServiceEndpoint (one host:port serving one AI capability).
 * Does not talk to {@code JobQueue} itself — it only spawns {@code
 * maxConcurrency} {@link AiServiceEndpointWorker} children (named {@code
 * address#0}, {@code address#1}, ...), each an independent completion-driven
 * single-job-at-a-time loop. Concurrency comes from running several workers
 * side by side, not from any one worker handling more than one job at once
 * (see {@code 018_concurrency_control/000_PerEndpointConcurrency_260810_oo01}).
 *
 * <p>Not abstract, unlike the workers it spawns — what varies per {@code
 * EndpointKind} (request path, {@code maxConcurrency}) is fully captured by
 * the {@code workerFactory} passed in, so this class itself has nothing left
 * to override.
 */
public final class AiServiceEndpoint {

    private final String address;
    private final int maxConcurrency;
    private final Supplier<AiServiceEndpointWorker> workerFactory;
    private ActorSystem system;
    private ActorRef<AiServiceEndpoint> self;

    public AiServiceEndpoint(String address, int maxConcurrency, Supplier<AiServiceEndpointWorker> workerFactory) {
        this.address = address;
        this.maxConcurrency = maxConcurrency;
        this.workerFactory = workerFactory;
    }

    /** Bind this actor's own reference; must run before {@link #start}. */
    public void bind(ActorSystem system, ActorRef<AiServiceEndpoint> self) {
        this.system = system;
        this.self = self;
    }

    /** Spawn every concurrency-slot worker and start each one. */
    public void start() {
        for (int i = 0; i < maxConcurrency; i++) {
            AiServiceEndpointWorker worker = workerFactory.get();
            ActorRef<AiServiceEndpointWorker> workerRef = self.createChild(address + "#" + i, worker);
            workerRef.tell(w -> w.bind(system, workerRef));
            workerRef.tell(AiServiceEndpointWorker::start);
        }
    }
}
