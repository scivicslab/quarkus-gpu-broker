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
 * Each {@code AiServiceEndpointWorker} pulls one job, blocks until it completes,
 * then pulls the next. With two endpoints, a third submitted job cannot
 * start until one frees up (completion-driven, N=1), and a foreground job
 * submitted under load overtakes a waiting background job at dispatch time.
 */
@DisplayName("AiServiceEndpointWorker dispatch — completion-driven pull with N=1")
class CompletionDrivenDispatchTest {

    private static Job job(Priority priority, String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                priority, new RecordingResponseSink());
    }

    /** Create an AiServiceEndpointWorker, bind its self-reference, and have it enter the idle set. */
    private static void spawnEndpoint(ActorSystem system, ActorRef<JobQueue> queue,
                                      LatchAiServiceClient client, String address) {
        AiServiceEndpointWorker endpoint = new AiServiceEndpointWorker(queue.getName(), address, client, "/v1/chat/completions");
        ActorRef<AiServiceEndpointWorker> ref = system.actorOf(address, endpoint);
        ref.tell(e -> e.bind(system, ref)).join();   // bind self before start
        ref.tell(AiServiceEndpointWorker::start);          // enter idle → requestWork
    }

    /**
     * Exactly what {@code ProxyResource}/{@code AsyncJobResource} do:
     * {@code JobQueue} never tells anyone itself, so the caller of {@code
     * submit} must dispatch to the returned {@code endpointId} if one comes
     * back immediately (an already-idle endpoint waiting for work).
     */
    private static void submitAndDispatch(ActorSystem system, ActorRef<JobQueue> queue, Job job) throws Exception {
        String endpointId = queue.ask(q -> q.submit(job)).get();
        if (endpointId != null) {
            ActorRef<AiServiceEndpointWorker> endpoint = system.getActor(endpointId);
            endpoint.tell(w -> w.assign(job));
        }
    }

    @Test
    void thirdJob_waitsUntilAnEndpointCompletes() throws Exception {
        ActorSystem system = new ActorSystem("broker-dispatch-test");
        LatchAiServiceClient client = new LatchAiServiceClient();
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());
        spawnEndpoint(system, queue, client, "node-a");
        spawnEndpoint(system, queue, client, "node-b");

        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j1"));
        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j2"));
        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "j3"));

        client.awaitStarted("j1");
        client.awaitStarted("j2");                       // both endpoints processing
        assertFalse(client.isStarted("j3"));             // no free endpoint → j3 waits in the deque
        assertEquals(1, client.maxConcurrentPerAddress()); // N=1: no endpoint runs two at once

        client.complete("j1");                           // one endpoint completes → it requestWork
        client.awaitStarted("j3");                       // j3 is now dispatched
        assertTrue(client.isStarted("j3"));
        assertEquals(1, client.maxConcurrentPerAddress()); // still N=1 after re-dispatch

        client.completeAll();
        system.terminate();
    }

    @Test
    void foregroundSubmittedUnderLoad_overtakesWaitingBackground() throws Exception {
        ActorSystem system = new ActorSystem("broker-dispatch-fg-test");
        LatchAiServiceClient client = new LatchAiServiceClient();
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());
        spawnEndpoint(system, queue, client, "node-a");
        spawnEndpoint(system, queue, client, "node-b");

        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "b1"));   // fills endpoint 1
        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "b2"));   // fills endpoint 2
        client.awaitStarted("b1");
        client.awaitStarted("b2");                       // both endpoints busy

        submitAndDispatch(system, queue, job(Priority.BACKGROUND, "b3"));  // waits in deque (back)
        submitAndDispatch(system, queue, job(Priority.FOREGROUND, "f1"));  // FG → front, ahead of b3

        client.complete("b1");                           // an endpoint frees up
        client.awaitStarted("f1");                       // FG is dispatched first
        assertTrue(client.isStarted("f1"));
        assertFalse(client.isStarted("b3"));              // BG still waiting behind FG

        client.completeAll();
        system.terminate();
    }
}
