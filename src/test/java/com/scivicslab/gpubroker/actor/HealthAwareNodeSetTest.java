package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * S_health: the QueueActor only assigns to active nodes. A withdrawn (detached
 * or failed) node receives no work; a failed assign is re-submitted to a healthy
 * node; a re-attached node receives work again.
 *
 * <p>Real health is ping-driven (external service); these unit tests drive it
 * directly with detach/attach and a failing stub upstream, touching no network.
 */
@Tag("S_health")
@DisplayName("HealthAwareNodeSet — active-only dispatch, withdraw/attach, failure re-submit (S_health)")
class HealthAwareNodeSetTest {

    /**
     * Create a NodeActor, bind its self-reference, and enter rotation.
     * {@code start()} is joined so its attach reaches the queue mailbox in spawn
     * order, making idle-node ordering deterministic across the test.
     */
    private static ActorRef<NodeActor> spawnNode(ActorSystem system, ActorRef<QueueActor> queue,
                                                 RecordingUpstream upstream, String name) {
        NodeActor node = new NodeActor(name, queue, upstream);
        ActorRef<NodeActor> ref = system.actorOf(name, node);
        ref.tell(n -> n.bind(ref)).join();   // bind self before start
        ref.tell(NodeActor::start).join();   // enqueue attach to queue in spawn order
        return ref;
    }

    @Test
    void detachedNode_isNeverAssigned() throws Exception {
        ActorSystem system = new ActorSystem("broker-health-detach-test");
        RecordingUpstream upstream = new RecordingUpstream();
        ActorRef<QueueActor> queue = system.actorOf("queue", new QueueActor());
        ActorRef<NodeActor> a = spawnNode(system, queue, upstream, "node-a");
        spawnNode(system, queue, upstream, "node-b");

        a.tell(NodeActor::detach).join();                   // detach node-a (handed to training)

        queue.tell(q -> q.submit(Job.bg("j1")));
        queue.tell(q -> q.submit(Job.bg("j2")));
        queue.tell(q -> q.submit(Job.bg("j3")));
        upstream.awaitCompleted(3);

        // Jobs are not bound to a node, so the surviving node picks up all of them
        // (this is also why detach needs no drain).
        assertTrue(upstream.nodesUsed().stream().allMatch(id -> id.equals("node-b")));
        assertFalse(upstream.nodesUsed().contains("node-a"));   // node-a got nothing
        system.terminate();
    }

    @Test
    void failedAssign_isReSubmittedToHealthyNode_andNodeLeavesRotation() throws Exception {
        ActorSystem system = new ActorSystem("broker-health-fail-test");
        RecordingUpstream upstream = new RecordingUpstream();
        upstream.failNode("node-a");                        // node-a fails every send
        ActorRef<QueueActor> queue = system.actorOf("queue", new QueueActor());
        spawnNode(system, queue, upstream, "node-a");       // first idle → gets j1 first
        spawnNode(system, queue, upstream, "node-b");

        queue.tell(q -> q.submit(Job.bg("j1")));            // → node-a fails → re-submit → node-b
        upstream.awaitCompleted(1);

        assertTrue(upstream.attempted("node-a"));           // node-a tried and threw
        assertEquals(List.of("node-b"), upstream.nodesUsed());  // completed only on node-b

        // node-a left rotation: subsequent jobs go only to node-b
        queue.tell(q -> q.submit(Job.bg("j2")));
        queue.tell(q -> q.submit(Job.bg("j3")));
        upstream.awaitCompleted(3);
        assertTrue(upstream.nodesUsed().stream().allMatch(id -> id.equals("node-b")));

        system.terminate();
    }

    @Test
    void reAttachedNode_receivesWorkAgain() throws Exception {
        ActorSystem system = new ActorSystem("broker-health-reattach-test");
        RecordingUpstream upstream = new RecordingUpstream();
        ActorRef<QueueActor> queue = system.actorOf("queue", new QueueActor());
        ActorRef<NodeActor> a = spawnNode(system, queue, upstream, "node-a");   // the only node

        a.tell(NodeActor::detach).join();                   // detach the only node

        queue.tell(q -> q.submit(Job.bg("j1"))).join();     // no active node → waits in deque
        assertEquals(0, upstream.completedCount());         // nothing dispatched while detached

        a.tell(NodeActor::attach).join();                   // back in service → picks up the waiter
        upstream.awaitCompleted(1);
        assertEquals(List.of("node-a"), upstream.nodesUsed());

        system.terminate();
    }
}
