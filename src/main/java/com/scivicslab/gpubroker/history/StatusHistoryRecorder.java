package com.scivicslab.gpubroker.history;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.gpubroker.boot.EndpointPoller;
import com.scivicslab.gpubroker.boot.EndpointSurveyor;
import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.config.EndpointProbe;
import com.scivicslab.gpubroker.model.QueueStatus;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Measures liveness once a minute and hands the reading, together with each
 * queue's {@code QueueSnapshot}, to {@link StatusHistoryStore}.
 *
 * <p>This is the caller {@code EndpointProbe.survey} was written for — its
 * Javadoc already says it is stateless and meant to be "called fresh at
 * startup and on every poll". Until this class existed, {@code
 * JobQueueRegistry.onStart} was the only caller, so nothing ever noticed an
 * endpoint that stopped answering while idle (see {@code
 * StatusHistory_260905_oo01}).
 *
 * <p>Recording only observes. Acting on the same reading — registering an address that answers
 * but is absent from {@code JobQueue}, or moving one whose node now serves a different model —
 * is a separate decision, made by {@code JobQueueRegistry.reconcile} before this class is called
 * ({@code PeriodicRediscovery_260923_oo01}). The probing itself is shared: {@link EndpointPoller}
 * surveys once a minute and hands the same result to both.
 */
@Singleton
public class StatusHistoryRecorder {

    private static final Logger LOG = Logger.getLogger(StatusHistoryRecorder.class.getName());

    @Inject
    JobQueueRegistry queues;

    @Inject
    ActorRef<StatusHistoryStore> store;

    /**
     * Writes one round's liveness and queue snapshots. Called by {@link EndpointPoller} with the
     * survey it already ran, after {@code JobQueueRegistry.reconcile} has acted on it, so the
     * registered set read here is the post-reconciliation one.
     */
    public void record(List<EndpointSurveyor.Found> found) {
        try {
            List<QueueStatus> statuses = queues.statusSnapshot();
            List<ProbeObservation> probes = probeAll(found, statuses);
            store.tell(s -> s.record(Instant.now(), probes, statuses));
        } catch (RuntimeException e) {
            // One failed round of observation must not stop later rounds.
            LOG.log(Level.SEVERE, "status history observation failed", e);
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        // Waits for completion (not a bare tell) so the last partial bucket is on disk before
        // ActorSystemProducer.onStop terminates the system this actor's mailbox thread lives on.
        store.tell(StatusHistoryStore::flush).join();
    }

    /**
     * One probe result per address worth drawing a liveness row for: every address that
     * answered any {@link EndpointProbe}, plus every address still registered in a {@code
     * JobQueue} — the latter answered nothing this round and is recorded as down.
     */
    private List<ProbeObservation> probeAll(List<EndpointSurveyor.Found> found, List<QueueStatus> statuses) {
        Map<String, String> responding = new LinkedHashMap<>();
        found.forEach(f -> responding.put(f.info().address(), f.info().queueName()));
        Map<String, String> registered = registeredAddresses(statuses);

        Map<String, ProbeObservation> observations = new LinkedHashMap<>();
        responding.forEach((address, queueName) ->
                observations.put(address, new ProbeObservation(address, queueName, true)));
        registered.forEach((address, queueName) ->
                observations.computeIfAbsent(address, a -> new ProbeObservation(a, queueName, false)));
        return List.copyOf(observations.values());
    }

    /**
     * {@code address} -> {@code queueName} for every worker still attached to a queue.
     * A worker's actor name is {@code address#slot}; several workers share one address, so the
     * suffix is stripped and the address recorded once.
     */
    private Map<String, String> registeredAddresses(List<QueueStatus> statuses) {
        Map<String, String> registered = new LinkedHashMap<>();
        for (QueueStatus status : statuses) {
            Set<String> workerIds = new HashSet<>(status.snapshot().activeEndpointIds());
            workerIds.addAll(status.snapshot().idleEndpointIds());
            for (String workerId : workerIds) {
                registered.put(physicalAddress(workerId), status.queueName());
            }
        }
        return registered;
    }

    private static String physicalAddress(String workerId) {
        int slot = workerId.lastIndexOf('#');
        return slot < 0 ? workerId : workerId.substring(0, slot);
    }
}
