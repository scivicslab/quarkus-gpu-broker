package com.scivicslab.gpubroker.history;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.EndpointProbe;
import com.scivicslab.gpubroker.model.QueueStatus;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
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
 * <p>Probing only observes. It deliberately does not register an address
 * that answers but is absent from {@code JobQueue} — putting a recovered
 * node back into rotation changes where jobs go, which is a separate
 * decision from what the status page shows.
 */
@Singleton
public class StatusHistoryRecorder {

    private static final Logger LOG = Logger.getLogger(StatusHistoryRecorder.class.getName());

    @Inject
    Instance<EndpointProbe> knownProbeBeans;

    @Inject
    JobQueueRegistry queues;

    @Inject
    BrokerConfig brokerConfig;

    @Inject
    ActorRef<StatusHistoryStore> store;

    @Scheduled(every = "1m")
    void observe() {
        try {
            List<QueueStatus> statuses = queues.statusSnapshot();
            List<ProbeObservation> probes = probeAll(statuses);
            store.tell(s -> s.record(Instant.now(), probes, statuses));
        } catch (RuntimeException e) {
            // A scheduled method that throws is retried but never recovers on its own;
            // one failed round of observation must not stop later rounds.
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
    private List<ProbeObservation> probeAll(List<QueueStatus> statuses) {
        Map<String, String> responding = surveyAll();
        Map<String, String> registered = registeredAddresses(statuses);

        Map<String, ProbeObservation> observations = new LinkedHashMap<>();
        responding.forEach((address, queueName) ->
                observations.put(address, new ProbeObservation(address, queueName, true)));
        registered.forEach((address, queueName) ->
                observations.computeIfAbsent(address, a -> new ProbeObservation(a, queueName, false)));
        return List.copyOf(observations.values());
    }

    /** {@code address} -> {@code queueName} for every endpoint that answered its probe. */
    private Map<String, String> surveyAll() {
        List<EndpointProbe> knownProbes = knownProbeBeans.stream().toList();
        List<String> nodeIps = queues.expandNodeIps();
        Map<String, BrokerConfig.EndpointCapability> capabilities = brokerConfig.capabilities();

        Map<String, String> found = new HashMap<>();
        try (ExecutorService kindSurveys = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<EndpointInfo>>> surveys = new ArrayList<>();
            for (EndpointProbe probe : knownProbes) {
                surveys.add(kindSurveys.submit(() -> probe.survey(nodeIps, capabilities)));
            }
            // (each kind's survey already probes its own addresses in parallel)
            for (Future<List<EndpointInfo>> survey : surveys) {
                for (EndpointInfo info : survey.get()) {
                    found.put(info.address(), info.queueName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.log(Level.SEVERE, "endpoint survey failed during status history observation", e);
        }
        return found;
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
