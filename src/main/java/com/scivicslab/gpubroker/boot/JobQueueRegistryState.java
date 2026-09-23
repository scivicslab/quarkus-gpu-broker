package com.scivicslab.gpubroker.boot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * {@link JobQueueRegistry}'s own mutable state: which queue name maps to which {@code JobQueue}
 * actor, each queue's display name, and whether the broker is draining.
 *
 * <p>A plain POJO, run as an actor ({@code JobQueueRegistryStateProducer} wraps it in the one
 * {@code ActorRef} every caller shares) for the same reason {@code JobQueue} itself is: many
 * request threads (every {@code ProxyResource}/{@code AsyncJobResource}/{@code
 * OpenAiCompatResource} call) read this concurrently with the thread that writes it (originally
 * CDI's {@code StartupEvent}/{@code ShutdownEvent} callbacks, via {@link JobQueueRegistry}).</p>
 */
public class JobQueueRegistryState {

    private final Map<String, ActorRef<JobQueue>> queues = new HashMap<>();
    private final Map<String, String> displayNames = new HashMap<>();
    /** address -> the queue it is currently registered in, so a later survey can tell whether
     *  that address moved to another model (see {@code PeriodicRediscovery_260923_oo01}). */
    private final Map<String, String> endpointQueues = new HashMap<>();
    private boolean draining = false;

    /** One queue's registration outcome: the {@code JobQueue} actor, and whether it was just created. */
    public record Registration(ActorRef<JobQueue> queue, boolean isNew) {}

    /**
     * Returns the existing queue for {@code queueName}, or creates one via {@code factory} — called
     * at most once, only when no queue is registered under that name yet.
     */
    public Registration registerQueue(String queueName, Supplier<ActorRef<JobQueue>> factory) {
        ActorRef<JobQueue> existing = queues.get(queueName);
        if (existing != null) {
            return new Registration(existing, false);
        }
        ActorRef<JobQueue> created = factory.get();
        queues.put(queueName, created);
        return new Registration(created, true);
    }

    public void putDisplayName(String queueName, String displayName) {
        displayNames.put(queueName, displayName);
    }

    /** Records that {@code address} now serves {@code queueName}. */
    public void putEndpoint(String address, String queueName) {
        endpointQueues.put(address, queueName);
    }

    /** Forgets {@code address}; returns the queue it was in, or null if it was not registered. */
    public String removeEndpoint(String address) {
        return endpointQueues.remove(address);
    }

    /** address -> queue name, for {@code JobQueueRegistry.reconcile}. */
    public Map<String, String> endpointQueues() {
        return Map.copyOf(endpointQueues);
    }

    /** Whether any address is still registered in {@code queueName}. */
    public boolean hasEndpoints(String queueName) {
        return endpointQueues.containsValue(queueName);
    }

    /**
     * Stops advertising and resolving {@code queueName}. The {@code JobQueue} actor itself is left
     * alone: a request thread may already hold its {@code ActorRef}, and an empty queue with no
     * workers simply parks whatever reaches it (see {@code PeriodicRediscovery_260923_oo01}).
     */
    public void removeQueue(String queueName) {
        queues.remove(queueName);
        displayNames.remove(queueName);
    }

    public ActorRef<JobQueue> get(String queueName) {
        return queues.get(queueName);
    }

    /** {@code queueName} -> {@code JobQueue} actor, for {@code JobQueueRegistry.statusSnapshot}. */
    public Map<String, ActorRef<JobQueue>> queueMap() {
        return Map.copyOf(queues);
    }

    /** Every registered queue, for shutdown draining. */
    public List<ActorRef<JobQueue>> allQueues() {
        return List.copyOf(queues.values());
    }

    public Map<String, String> displayNames() {
        return Map.copyOf(displayNames);
    }

    public void setDraining() {
        draining = true;
    }

    public boolean isDraining() {
        return draining;
    }
}
