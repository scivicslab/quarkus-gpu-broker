package com.scivicslab.gpubroker.boot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.config.ChatQueueName;
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
    /** queue -> the longest prompt-plus-reply window its endpoints accept; absent when unknown. */
    private final Map<String, Integer> contextLengths = new HashMap<>();
    /** address -> the queue it is currently registered in, so a later survey can tell whether
     *  that address moved to another model (see {@code PeriodicRediscovery_260923_oo01}). */
    private final Map<String, String> endpointQueues = new HashMap<>();
    /** address -> how many rounds of probing in a row it has answered nothing, for
     *  {@link #staleAddresses}. Absent means zero, the same as a fresh entry. */
    private final Map<String, Integer> consecutiveMisses = new HashMap<>();
    private boolean draining = false;

    /** How many consecutive rounds of probing a registered address may answer nothing before
     *  {@link #staleAddresses} reports it. One round is one minute of silence
     *  ({@code EndpointPoller}), so three is three minutes -- enough that a probe request
     *  delayed behind a real job the endpoint is still busy running (measured: 8 s under four
     *  concurrent Marker requests against a 2 s probe timeout) is not mistaken for a dead
     *  endpoint, and short enough that an endpoint stopped outright (not merely restarting)
     *  stops being handed jobs within minutes instead of staying registered forever. See
     *  {@code PeriodicRediscovery_260923_oo01} "何回の無応答で外すか". */
    static final int MISSES_BEFORE_STALE = 3;

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

    /**
     * Records this queue's context length. The endpoints of one queue serve the same model and are
     * expected to agree; when they do not, the smallest wins, because a prompt that fits only the
     * larger one fails whenever the queue hands it to the other.
     */
    public void putContextLength(String queueName, int contextLength) {
        if (contextLength <= 0) {
            return;
        }
        contextLengths.merge(queueName, contextLength, Math::min);
    }

    /** Records that {@code address} now serves {@code queueName}. */
    public void putEndpoint(String address, String queueName) {
        endpointQueues.put(address, queueName);
    }

    /** Forgets {@code address}; returns the queue it was in, or null if it was not registered. */
    public String removeEndpoint(String address) {
        consecutiveMisses.remove(address);
        return endpointQueues.remove(address);
    }

    /**
     * Records one round of probing against every currently registered address, and returns the
     * ones that have now missed {@value #MISSES_BEFORE_STALE} rounds running. Call once per
     * round, before acting on {@link ReconcilePlan#of} -- both read the same registered-address
     * snapshot, and a caller that withdraws a {@code staleAddresses} result first will simply
     * find nothing left to change for it afterward.
     *
     * @param answered every address that answered this round, regardless of which queue it now
     *                 claims; an address in here has its miss count reset to zero
     * @return registered addresses whose miss count has just reached {@value #MISSES_BEFORE_STALE}
     *         or beyond
     */
    public List<String> staleAddresses(Set<String> answered) {
        List<String> stale = new ArrayList<>();
        for (String address : endpointQueues.keySet()) {
            if (answered.contains(address)) {
                consecutiveMisses.remove(address);
                continue;
            }
            int misses = consecutiveMisses.merge(address, 1, Integer::sum);
            if (misses >= MISSES_BEFORE_STALE) {
                stale.add(address);
            }
        }
        return stale;
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
        contextLengths.remove(queueName);
    }

    /**
     * The {@code JobQueue} a caller named, or null. A name carrying the old {@code vllm-} prefix is
     * looked up again under the current one ({@link ChatQueueName#resolve}), so a client built
     * before the rename keeps working until it is rebuilt.
     */
    public ActorRef<JobQueue> get(String queueName) {
        ActorRef<JobQueue> queue = queues.get(queueName);
        return queue != null ? queue : queues.get(ChatQueueName.resolve(queueName));
    }

    /** {@code queueName} -> {@code JobQueue} actor, for {@code JobQueueRegistry.statusSnapshot}. */
    public Map<String, ActorRef<JobQueue>> queueMap() {
        return byName(queues);
    }

    /** Every registered queue, for shutdown draining. */
    public List<ActorRef<JobQueue>> allQueues() {
        return List.copyOf(queues.values());
    }

    public Map<String, Integer> contextLengths() {
        return byName(contextLengths);
    }

    public Map<String, String> displayNames() {
        return byName(displayNames);
    }

    public void setDraining() {
        draining = true;
    }

    public boolean isDraining() {
        return draining;
    }

    /**
     * A copy ordered by queue name. The callers of these two maps put what they hold on a screen or
     * in a JSON array -- {@code GET /queues}, the status page, {@code GET /v1/models} -- and a reader
     * comparing two runs needs the same queue in the same place. A {@code HashMap} orders by hash,
     * and {@code Map.copyOf} salts its iteration order afresh in every JVM, so the broker used to
     * list its queues in a different order after each restart.
     */
    private static <V> Map<String, V> byName(Map<String, V> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }
}
