package com.scivicslab.gpubroker.boot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.actor.AiServiceEndpoint;
import com.scivicslab.gpubroker.actor.AiServiceEndpointBuilder;
import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.actor.ROOT;
import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.EndpointProbe;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.QueueStatus;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Builds the Actor tree at startup by surveying every known {@link
 * EndpointProbe} kind across every configured node, and drains it
 * gracefully at shutdown.
 *
 * <p>The only place in the codebase that {@code new}s a {@code ROOT} or a
 * {@code JobQueue} and wires it in with {@code actorOf}/{@code createChild}
 * — for the same reason {@code ActorSystemProducer} is the sole place that
 * {@code new}s the {@code ActorSystem}: a single, findable place to reason
 * about the tree's shape. {@code AiServiceEndpoint} instances themselves are
 * {@code new}'d by {@link AiServiceEndpointBuilder}, not here — this class
 * only {@code createChild}s the result.
 */
@Singleton
public class JobQueueRegistry {

    private static final Logger LOG = Logger.getLogger(JobQueueRegistry.class);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ActorSystem system;

    // CDI has no plain "inject every bean implementing this interface as a List" — only
    // Instance<T> (iterable) is standard. Converted to a List once per onStart, since the
    // survey loop below needs indexed access to pair each probe with its own Future.
    @Inject
    Instance<EndpointProbe> knownProbeBeans;

    @Inject
    BrokerConfig brokerConfig;

    @Inject
    AiServiceEndpointBuilder builder;

    private final Map<String, ActorRef<JobQueue>> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);

    void onStart(@Observes StartupEvent event) {
        ActorRef<ROOT> root = system.actorOf("root", new ROOT());
        List<EndpointProbe> knownProbes = knownProbeBeans.stream().toList();
        List<String> expandedNodeIps = expandNodeIps();
        Map<String, BrokerConfig.EndpointCapability> capabilities = brokerConfig.capabilities();

        // Each EndpointProbe.survey already probes its own nodeIp x conventionalPort
        // combinations in parallel; run the (few) known kinds' surveys in parallel too,
        // so total startup time stays close to the single slowest kind, not their sum.
        try (ExecutorService kindSurveys = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<EndpointInfo>>> surveys = new ArrayList<>();
            for (EndpointProbe probe : knownProbes) {
                surveys.add(kindSurveys.submit(() -> probe.survey(expandedNodeIps, capabilities)));
            }
            for (int i = 0; i < knownProbes.size(); i++) {
                EndpointProbe probe = knownProbes.get(i);
                for (EndpointInfo info : surveys.get(i).get()) {
                    registerEndpoint(root, probe, info);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("endpoint survey failed unexpectedly", e);
        }
    }

    private List<String> expandNodeIps() {
        List<String> expanded = new ArrayList<>();
        for (String entry : brokerConfig.nodes().orElse(List.of())) {
            expanded.addAll(CidrRange.expand(entry));
        }
        return expanded;
    }

    void onShutdown(@Observes ShutdownEvent event) {
        draining.set(true);
        for (ActorRef<JobQueue> queue : queues.values()) {
            List<Job> pending = queue.ask(JobQueue::drainPending).join();
            pending.forEach(job -> job.responseSink().fail(new DrainingException()));
        }
        awaitIdle(DRAIN_TIMEOUT);
    }

    public boolean isDraining() {
        return draining.get();
    }

    public ActorRef<JobQueue> get(String queueName) {
        return queues.get(queueName);
    }

    /** One {@link QueueStatus} per registered queue, for {@code GET /status}. */
    public List<QueueStatus> statusSnapshot() {
        return queues.entrySet().stream()
                .map(e -> new QueueStatus(e.getKey(), e.getValue().ask(JobQueue::snapshot).join()))
                .toList();
    }

    private void registerEndpoint(ActorRef<ROOT> root, EndpointProbe probe, EndpointInfo info) {
        boolean[] isNewQueue = {false};
        ActorRef<JobQueue> queue = queues.computeIfAbsent(info.queueName(), n -> {
            isNewQueue[0] = true;
            return root.createChild(n, new JobQueue());
        });
        if (isNewQueue[0]) {
            queue.tell(q -> q.bind(system, queue));
            queue.tell(JobQueue::startReconciliation);
        }
        AiServiceEndpoint endpoint = builder.build(info, probe.requestPath());
        ActorRef<AiServiceEndpoint> endpointRef = queue.createChild(info.address(), endpoint);
        endpointRef.tell(e -> e.bind(system, endpointRef));
        endpointRef.tell(AiServiceEndpoint::start);
        LOG.infof("discovered %s at %s -> queue %s (maxConcurrency=%d)",
                probe.getClass().getSimpleName(), info.address(), info.queueName(), info.maxConcurrency());
    }

    private void awaitIdle(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline) && !allQueuesIdle()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean allQueuesIdle() {
        return queues.values().stream().allMatch(q -> q.ask(JobQueue::isIdle).join());
    }
}
