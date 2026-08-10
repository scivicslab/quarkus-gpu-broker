package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * S_dispatch: each NodeActor pulls one job, blocks until it completes, then
 * pulls the next. With two nodes, a third submitted job cannot start until a
 * node frees up (completion-driven, N=1), and a foreground job submitted under
 * load overtakes a waiting background job at dispatch time.
 */
@Tag("S_dispatch")
@DisplayName("CompletionDrivenDispatch — completion-driven pull with N=1 (S_dispatch)")
class CompletionDrivenDispatchTest {

    /** Create a NodeActor, bind its self-reference, and have it enter the idle set. */
    private static void spawnNode(ActorSystem system, ActorRef<QueueActor> queue,
                                  LatchUpstream upstream, String name) {
        NodeActor node = new NodeActor(name, queue, upstream);
        ActorRef<NodeActor> ref = system.actorOf(name, node);
        ref.tell(n -> n.bind(ref)).join();   // bind self before start
        ref.tell(NodeActor::start);          // enter idle → requestWork
    }

    @Test
    void thirdJob_waitsUntilANodeCompletes() throws Exception {
        ActorSystem system = new ActorSystem("broker-dispatch-test");
        LatchUpstream upstream = new LatchUpstream();
        ActorRef<QueueActor> queue = system.actorOf("queue", new QueueActor());
        spawnNode(system, queue, upstream, "node-a");
        spawnNode(system, queue, upstream, "node-b");

        queue.tell(q -> q.submit(Job.bg("j1")));
        queue.tell(q -> q.submit(Job.bg("j2")));
        queue.tell(q -> q.submit(Job.bg("j3")));

        upstream.awaitStarted("j1");
        upstream.awaitStarted("j2");                       // both nodes processing
        assertFalse(upstream.isStarted("j3"));             // no free node → j3 waits in the deque
        assertEquals(1, upstream.maxConcurrentPerNode());  // N=1: no node runs two at once

        upstream.complete("j1");                           // one node completes → it requestWork
        upstream.awaitStarted("j3");                       // j3 is now dispatched
        assertTrue(upstream.isStarted("j3"));
        assertEquals(1, upstream.maxConcurrentPerNode());  // still N=1 after re-dispatch

        upstream.completeAll();
        system.terminate();
    }

    @Test
    void foregroundSubmittedUnderLoad_overtakesWaitingBackground() throws Exception {
        ActorSystem system = new ActorSystem("broker-dispatch-fg-test");
        LatchUpstream upstream = new LatchUpstream();
        ActorRef<QueueActor> queue = system.actorOf("queue", new QueueActor());
        spawnNode(system, queue, upstream, "node-a");
        spawnNode(system, queue, upstream, "node-b");

        queue.tell(q -> q.submit(Job.bg("b1")));           // fills node 1
        queue.tell(q -> q.submit(Job.bg("b2")));           // fills node 2
        upstream.awaitStarted("b1");
        upstream.awaitStarted("b2");                       // both nodes busy

        queue.tell(q -> q.submit(Job.bg("b3")));           // waits in deque (back)
        queue.tell(q -> q.submit(Job.fg("f1"))).join();    // FG → front, ahead of b3

        upstream.complete("b1");                           // a node frees up
        upstream.awaitStarted("f1");                       // FG is dispatched first
        assertTrue(upstream.isStarted("f1"));
        assertFalse(upstream.isStarted("b3"));             // BG still waiting behind FG

        upstream.completeAll();
        system.terminate();
    }
}
