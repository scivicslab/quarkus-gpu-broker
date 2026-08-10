package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * A failing {@code AiServiceEndpointWorker} does not lose the job: {@code
 * AiServiceEndpointWorker.requeue} hands it to whichever endpoint is (or later
 * becomes) idle. A permanently failing endpoint does not retry forever
 * either — {@code MAX_ATTEMPTS} bounds it, and the {@code ResponseSink} is
 * failed explicitly once exhausted.
 */
@DisplayName("AiServiceEndpointWorker failure handling — requeue and retry limit")
class HealthAwareNodeSetTest {

    private static Job job(String label, RecordingResponseSink sink) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"), Priority.BACKGROUND, sink);
    }

    private static void spawnEndpoint(ActorSystem system, ActorRef<JobQueue> queue, AiServiceEndpointWorker endpoint, String address) {
        ActorRef<AiServiceEndpointWorker> ref = system.actorOf(address, endpoint);
        ref.tell(e -> e.bind(system, ref)).join();   // bind self before start
        ref.tell(AiServiceEndpointWorker::start);          // enter idle → requestWork
    }

    /** Same submit-then-dispatch pattern ProxyResource/AsyncJobResource use. */
    private static void submitAndDispatch(ActorSystem system, ActorRef<JobQueue> queue, Job job) throws Exception {
        String endpointId = queue.ask(q -> q.submit(job)).get();
        if (endpointId != null) {
            ActorRef<AiServiceEndpointWorker> endpoint = system.getActor(endpointId);
            endpoint.tell(w -> w.assign(job));
        }
    }

    private static void awaitOutcome(RecordingResponseSink sink) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!sink.isCompleted() && !sink.isFailed()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("job did not reach a final outcome within the timeout");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void unhealthyEndpoint_requeuesToHealthyEndpoint() throws Exception {
        ActorSystem system = new ActorSystem("broker-health-requeue-test");
        FlakyAiServiceClient client = new FlakyAiServiceClient();
        client.markUnhealthy("bad");
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());

        spawnEndpoint(system, queue, new VllmChatEndpointWorker(queue.getName(), "bad", client), "bad");

        RecordingResponseSink sink = new RecordingResponseSink();
        submitAndDispatch(system, queue, job("j1", sink));   // the only endpoint so far → fails → requeues into the deque

        spawnEndpoint(system, queue, new VllmChatEndpointWorker(queue.getName(), "good", client), "good");   // now free to pick it up

        awaitOutcome(sink);
        assertTrue(sink.isCompleted());
        assertFalse(sink.isFailed());
        assertEquals("j1", sink.bodyAsString());

        system.terminate();
    }

    @Test
    void permanentlyFailingEndpoint_givesUpAfterMaxAttempts() throws Exception {
        ActorSystem system = new ActorSystem("broker-health-retry-limit-test");
        FlakyAiServiceClient client = new FlakyAiServiceClient();
        client.markUnhealthy("bad");
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());

        spawnEndpoint(system, queue, new VllmChatEndpointWorker(queue.getName(), "bad", client), "bad");   // the only endpoint, ever

        RecordingResponseSink sink = new RecordingResponseSink();
        submitAndDispatch(system, queue, job("j1", sink));

        awaitOutcome(sink);
        assertTrue(sink.isFailed());
        assertFalse(sink.isCompleted());

        system.terminate();
    }
}
