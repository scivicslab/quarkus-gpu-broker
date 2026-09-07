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
