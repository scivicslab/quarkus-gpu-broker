package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;

/**
 * S_base: the priority deque lets a foreground job overtake the background
 * backlog while background jobs keep their FIFO order among themselves.
 */
@Tag("S_base")
@DisplayName("QueueActor priority deque — FG overtakes BG (S_base)")
class QueueActorPriorityTest {

    @Test
    void submit_fgAfterBg_pollsFgFirst() {
        QueueActor queue = new QueueActor();
        queue.submit(Job.bg("bg-1"));
        queue.submit(Job.bg("bg-2"));
        queue.submit(Job.fg("fg-1"));   // arrives last, but is FG

        assertEquals("fg-1", queue.pollNext().id());  // FG overtakes
        assertEquals("bg-1", queue.pollNext().id());  // BG stays FIFO
        assertEquals("bg-2", queue.pollNext().id());
        assertNull(queue.pollNext());                 // empty
    }
}
