package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * {@code AiServiceEndpoint} spawns {@code maxConcurrency} {@code
 * AiServiceEndpointWorker} children sharing one physical address, so a
 * single physical node can genuinely run more than one job at once — the
 * whole point of {@code 018_concurrency_control/000_PerEndpointConcurrency_260810_oo01}.
 * {@code JobQueue} itself is untouched; this test only exercises the new
 * spawn/dispatch wiring.
 */
@DisplayName("AiServiceEndpoint — spawns maxConcurrency workers sharing one physical address")
class AiServiceEndpointConcurrencyTest {

    private static Job job(Priority priority, String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                priority, new RecordingResponseSink());
    }

    private static void spawnPhysicalEndpoint(ActorSystem system, ActorRef<JobQueue> queue,
                                               LatchAiServiceClient client, String address, int maxConcurrency) {
        AiServiceEndpoint endpoint = new AiServiceEndpoint(address, maxConcurrency,
                () -> new VllmChatEndpointWorker(queue.getName(), address, client));
        ActorRef<AiServiceEndpoint> ref = system.actorOf(address, endpoint);
        ref.tell(e -> e.bind(system, ref)).join();
        ref.tell(AiServiceEndpoint::start).join();
    }

    private static void submitAndDispatch(ActorSystem system, ActorRef<JobQueue> queue, Job job) throws Exception {
        String endpointId = queue.ask(q -> q.submit(job)).get();
        if (endpointId != null) {
            ActorRef<AiServiceEndpointWorker> worker = system.getActor(endpointId);
            worker.tell(w -> w.assign(job));
        }
    }

    @Test
    void oneAddressWithMaxConcurrencyThree_runsThreeJobsAtOnce() throws Exception {
        ActorSystem system = new ActorSystem("broker-concurrency-test");
        LatchAiServiceClient client = new LatchAiServiceClient();
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());
        spawnPhysicalEndpoint(system, queue, client, "node-a", 3);

        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j1"));
        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j2"));
        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j3"));
        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j4"));   // no slot free → waits

        client.awaitStarted("j1");
        client.awaitStarted("j2");
        client.awaitStarted("j3");
        assertFalse(client.isStarted("j4"));
        assertEquals(3, client.maxConcurrentPerAddress());   // all 3 slots of the SAME address ran at once

        client.complete("j1");
        client.awaitStarted("j4");
        assertTrue(client.isStarted("j4"));

        client.completeAll();
        system.terminate();
    }
}
