package com.scivicslab.gpubroker.actor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * <p>A single concrete class, like {@link AiServiceEndpointWorker} — what
 * varies per {@code EndpointProbe} kind (request path, {@code
 * maxConcurrency}) is fully captured by the {@code workerFactory} passed in
 * by {@code AiServiceEndpointBuilder}, so this class itself has nothing left
 * to override.
 */
public final class AiServiceEndpoint {

    /**
     * What {@link #resize} did.
     *
     * @param before  workers in rotation before the call
     * @param after   workers in rotation after the call
     * @param retired the names of the workers taken out of rotation; the caller withdraws them
     *                from the queue (see {@link #resize})
     */
    public record Resized(int before, int after, List<String> retired) {}

    private final String address;
    private final int maxConcurrency;
    private final Supplier<AiServiceEndpointWorker> workerFactory;
    private ActorSystem system;
    private ActorRef<AiServiceEndpoint> self;

    /** Names of the workers currently in rotation, the most recently added last. */
    private final Deque<String> rotation = new ArrayDeque<>();
    /** The next worker's index. Never reused, so a retired worker's name is never taken again. */
    private int nextIndex;

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
        resize(maxConcurrency);
    }

    /**
     * Brings the number of workers in rotation to {@code newMaxConcurrency}.
     *
     * <p>Growing spawns and starts new workers. Shrinking only <em>names</em> the workers to
     * retire and returns them: the caller withdraws each from the queue, which stops the queue
     * handing it work. A retired worker that is in the middle of a job finishes that job, asks
     * the queue for more, is refused, and parks. It is not closed, because closing interrupts the
     * call and fails a job that would have succeeded. Called from this actor's own mailbox, so it
     * is serialized with everything else this endpoint does.
     */
    public Resized resize(int newMaxConcurrency) {
        int before = rotation.size();
        List<String> retired = new ArrayList<>();
        while (rotation.size() > newMaxConcurrency) {
            retired.add(rotation.removeLast());
        }
        while (rotation.size() < newMaxConcurrency) {
            spawnWorker();
        }
        return new Resized(before, rotation.size(), List.copyOf(retired));
    }

    private void spawnWorker() {
        String name = address + "#" + nextIndex++;
        AiServiceEndpointWorker worker = workerFactory.get();
        ActorRef<AiServiceEndpointWorker> workerRef = self.createChild(name, worker);
        workerRef.tell(w -> w.bind(system, workerRef));
        workerRef.tell(AiServiceEndpointWorker::start);
        rotation.addLast(name);
    }
}
