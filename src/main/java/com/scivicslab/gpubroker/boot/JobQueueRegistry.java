package com.scivicslab.gpubroker.boot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.actor.AiServiceEndpoint;
import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.actor.ROOT;
import com.scivicslab.gpubroker.config.EndpointKind;
import com.scivicslab.gpubroker.llm.AiServiceClient;
import com.scivicslab.gpubroker.llm.HttpAiServiceClient;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Builds the Actor tree at startup by probing every configured node for
 * every known {@link EndpointKind}, and drains it gracefully at shutdown.
 *
 * <p>The only place in the codebase that {@code new}s a {@code ROOT}, a
 * {@code JobQueue} or an {@code AiServiceEndpoint} and wires it in with
 * {@code actorOf}/{@code createChild} — for the same reason {@code
 * ActorSystemProducer} is the sole place that {@code new}s the {@code
 * ActorSystem}: a single, findable place to reason about the tree's shape.
 */
@Singleton
public class JobQueueRegistry {

    private static final Logger LOG = Logger.getLogger(JobQueueRegistry.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ActorSystem system;

    @ConfigProperty(name = "broker.nodes")
    Optional<List<String>> nodeIps;

    private final HttpClient probeClient = HttpClient.newHttpClient();
    private final Map<String, ActorRef<JobQueue>> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);

    void onStart(@Observes StartupEvent event) {
        ActorRef<ROOT> root = system.actorOf("root", new ROOT());

        for (String nodeIp : nodeIps.orElse(List.of())) {
            for (EndpointKind kind : EndpointKind.values()) {
                String address = nodeIp + ":" + kind.conventionalPort();
                probe(address, kind).ifPresent(queueName -> registerEndpoint(root, queueName, address, kind));
            }
        }
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

    private void registerEndpoint(ActorRef<ROOT> root, String queueName, String address, EndpointKind kind) {
        ActorRef<JobQueue> queue = queues.computeIfAbsent(queueName, n -> root.createChild(n, new JobQueue()));
        AiServiceClient client = new HttpAiServiceClient();
        AiServiceEndpoint endpoint = kind.createEndpoint(queueName, address, client);
        ActorRef<AiServiceEndpoint> endpointRef = queue.createChild(address, endpoint);
        endpointRef.tell(e -> e.bind(system, endpointRef));
        endpointRef.tell(AiServiceEndpoint::start);
        LOG.infof("discovered %s at %s -> queue %s", kind, address, queueName);
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

    private Optional<String> probe(String address, EndpointKind kind) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address + kind.probePath()))
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = probeClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Optional.empty();   // not reachable — not this kind at this address
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        try {
            return Optional.of(kind.deriveQueueName(response.body()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();   // responded, but not in the shape this kind expects
        }
    }
}
