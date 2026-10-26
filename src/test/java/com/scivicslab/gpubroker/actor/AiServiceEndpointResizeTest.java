package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * Changing an endpoint's {@code max-concurrency} while the broker runs: more workers when it goes
 * up, fewer when it goes down, and a job already running on a worker that is being retired is left
 * to finish ({@code tasks/todo.md}, "broker を再起動せずに max-concurrency の変更を反映する").
 */
@Tag("ResizeWithoutRestart_261025_oo01")
@DisplayName("AiServiceEndpoint — resize the number of workers in rotation without a restart")
class AiServiceEndpointResizeTest {

    private static Job job(String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                Priority.BACKGROUND, new RecordingResponseSink());
    }

    private static void submit(ActorSystem system, ActorRef<JobQueue> queue, Job job) throws Exception {
        String endpointId = queue.ask(q -> q.submit(job)).get();
        if (endpointId != null) {
            ActorRef<AiServiceEndpointWorker> worker = system.getActor(endpointId);
            worker.tell(w -> w.assign(job));
        }
    }

    private static ActorRef<AiServiceEndpoint> spawn(ActorSystem system, ActorRef<JobQueue> queue,
                                                      LatchAiServiceClient client, String address, int max) {
        AiServiceEndpoint endpoint = new AiServiceEndpoint(address, max,
                () -> new AiServiceEndpointWorker(queue.getName(), address, client, "/v1/chat/completions"));
        ActorRef<AiServiceEndpoint> ref = system.actorOf(address, endpoint);
        ref.tell(e -> e.bind(system, ref)).join();
        ref.tell(AiServiceEndpoint::start).join();
        return ref;
    }

    @Test
    @DisplayName("raising 2 to 4 lets four jobs run at once")
    void growing_addsWorkers() throws Exception {
        ActorSystem system = new ActorSystem("resize-grow");
        LatchAiServiceClient client = new LatchAiServiceClient();
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());
        ActorRef<AiServiceEndpoint> endpoint = spawn(system, queue, client, "node-a", 2);

        AiServiceEndpoint.Resized resized = endpoint.ask(e -> e.resize(4)).get();
        assertEquals(2, resized.before());
        assertEquals(4, resized.after());
        assertTrue(resized.retired().isEmpty());

        for (String label : new String[] {"j1", "j2", "j3", "j4"}) {
            submit(system, queue, job(label));
        }
        for (String label : new String[] {"j1", "j2", "j3", "j4"}) {
            client.awaitStarted(label);
        }
        assertEquals(4, client.maxConcurrentPerAddress());

        client.completeAll();
        system.terminate();
    }

    @Test
    @DisplayName("lowering 3 to 1 names the two newest workers; a job they are running still finishes")
    void shrinking_retiresNewestWorkers_andLetsRunningJobsFinish() throws Exception {
        ActorSystem system = new ActorSystem("resize-shrink");
        LatchAiServiceClient client = new LatchAiServiceClient();
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());
        ActorRef<AiServiceEndpoint> endpoint = spawn(system, queue, client, "node-a", 3);

        submit(system, queue, job("j1"));
        submit(system, queue, job("j2"));
        submit(system, queue, job("j3"));
        client.awaitStarted("j1");
        client.awaitStarted("j2");
        client.awaitStarted("j3");

        AiServiceEndpoint.Resized resized = endpoint.ask(e -> e.resize(1)).get();
        assertEquals(3, resized.before());
        assertEquals(1, resized.after());
        assertEquals(java.util.List.of("node-a#2", "node-a#1"), resized.retired());
        for (String worker : resized.retired()) {
            queue.tell(q -> q.withdraw(worker)).join();
        }

        // Everything in flight is allowed to finish; none of it was interrupted.
        client.completeAll();
        submit(system, queue, job("j4"));
        submit(system, queue, job("j5"));
        submit(system, queue, job("j6"));
        client.awaitStarted("j4");
        assertEquals(1, queue.ask(JobQueue::totalSlots).get(), "only the surviving worker is attached");

        // With one worker left, j5 cannot start until j4 is done.
        assertFalse(client.isStarted("j5"));
        client.complete("j4");
        client.awaitStarted("j5");
        assertFalse(client.isStarted("j6"));

        client.completeAll();
        system.terminate();
    }

    @Test
    @DisplayName("resizing to the number already in rotation changes nothing")
    void sameSize_isANoOp() throws Exception {
        ActorSystem system = new ActorSystem("resize-same");
        LatchAiServiceClient client = new LatchAiServiceClient();
        ActorRef<JobQueue> queue = system.actorOf("queue", new JobQueue());
        ActorRef<AiServiceEndpoint> endpoint = spawn(system, queue, client, "node-a", 2);

        AiServiceEndpoint.Resized resized = endpoint.ask(e -> e.resize(2)).get();

        assertEquals(2, resized.before());
        assertEquals(2, resized.after());
        assertTrue(resized.retired().isEmpty());
        assertEquals(2, queue.ask(JobQueue::totalSlots).get());
        system.terminate();
    }
}
