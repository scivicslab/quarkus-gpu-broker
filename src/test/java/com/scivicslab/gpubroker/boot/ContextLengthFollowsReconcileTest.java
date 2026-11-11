package com.scivicslab.gpubroker.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.actor.AiServiceEndpointBuilder;
import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.EndpointProbe;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The context length a queue advertises ({@code GET /v1/models}' {@code max_model_len}) follows each
 * round of probing, without a broker restart: it is the smallest value the endpoints in that queue
 * report now, so it rises as well as falls, and it is gone with the queue's last endpoint
 * ({@code PeriodicRediscovery_260923_oo01}).
 *
 * <p>Drives the real {@link JobQueueRegistry#onStart} and {@link JobQueueRegistry#reconcile} with a
 * real {@code ActorSystem}; only the survey is a stand-in, so nothing reaches the network.</p>
 */
@Tag("PeriodicRediscovery_260923_oo01")
class ContextLengthFollowsReconcileTest {

    private static final String QUEUE = "chat-GLM-5.3-Flash-Uncensored";

    /** Stands in for a real probe: nothing here reaches the network. */
    private static final class StubProbe implements EndpointProbe {
        @Override public int conventionalPort() { return 8000; }
        @Override public String probePath() { return "/v1/models"; }
        @Override public String requestPath() { return "/v1/chat/completions"; }
        @Override public Optional<String> deriveQueueName(String body) { return Optional.empty(); }
        @Override public int defaultMaxConcurrency() { return 1; }
    }

    /** Answers the startup survey with a fixed list. */
    private static final class FixedSurveyor extends EndpointSurveyor {
        private final List<Found> atStartup;
        FixedSurveyor(List<Found> atStartup) { this.atStartup = atStartup; }
        @Override public List<Found> surveyNow() { return atStartup; }
    }

    private static final EndpointProbe PROBE = new StubProbe();

    private ActorSystem system;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("context-length-follows-reconcile");
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private static EndpointSurveyor.Found found(String address, String queueName, int contextLength) {
        return new EndpointSurveyor.Found(PROBE, new EndpointInfo(address, queueName, queueName, 1, contextLength));
    }

    /** A registry that has run its startup survey, which found {@code atStartup}. */
    private JobQueueRegistry startedWith(EndpointSurveyor.Found... atStartup) {
        JobQueueRegistry registry = new JobQueueRegistry();
        registry.system = system;
        registry.state = system.actorOf("job-queue-registry-state", new JobQueueRegistryState());
        registry.builder = new AiServiceEndpointBuilder();
        registry.surveyor = new FixedSurveyor(List.of(atStartup));
        registry.onStart(null);
        return registry;
    }

    @Test
    void aContextLengthRaisedInTheConfigIsAdvertisedAfterTheNextReconcile() {
        JobQueueRegistry registry = startedWith(found("192.168.5.16:8000", QUEUE, 262144));
        assertEquals(262144, registry.contextLengths().get(QUEUE));

        // The ConfigMap now declares 1048576 for the same address; the next survey reports it.
        registry.reconcile(List.of(found("192.168.5.16:8000", QUEUE, 1048576)));

        assertEquals(1048576, registry.contextLengths().get(QUEUE));
    }

    @Test
    void aContextLengthLoweredInTheConfigIsAdvertisedAfterTheNextReconcile() {
        JobQueueRegistry registry = startedWith(found("192.168.5.16:8000", QUEUE, 262144));

        registry.reconcile(List.of(found("192.168.5.16:8000", QUEUE, 131072)));

        assertEquals(131072, registry.contextLengths().get(QUEUE));
    }

    @Test
    void aQueueAdvertisesTheSmallestOfItsEndpointsAndRisesWhenTheSmallerOneLeaves() {
        JobQueueRegistry registry = startedWith(
                found("192.168.5.16:8000", QUEUE, 262144),
                found("192.168.5.17:8000", QUEUE, 131072));
        assertEquals(131072, registry.contextLengths().get(QUEUE));

        // 5.17 now serves another model: it leaves the queue, and so does its smaller window.
        registry.reconcile(List.of(
                found("192.168.5.16:8000", QUEUE, 262144),
                found("192.168.5.17:8000", "chat-other-model", 131072)));

        assertEquals(262144, registry.contextLengths().get(QUEUE));
        assertEquals(131072, registry.contextLengths().get("chat-other-model"));
    }

    @Test
    void aQueueWithNoEndpointsLeftAdvertisesNoContextLength() {
        JobQueueRegistry registry = startedWith(found("192.168.5.16:8000", QUEUE, 262144));

        registry.reconcile(List.of(found("192.168.5.16:8000", "chat-other-model", 131072)));

        assertFalse(registry.contextLengths().containsKey(QUEUE));
        assertEquals(131072, registry.contextLengths().get("chat-other-model"));
    }
}
