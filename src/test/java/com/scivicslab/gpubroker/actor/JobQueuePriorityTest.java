package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * Pure-POJO tests of {@link JobQueue}: priority ordering, immediate dispatch
 * to an idle endpoint, FG-reservation, and graceful-drain support. No
 * ActorSystem involved — {@code submit}/{@code requestWork}/{@code attach}
 * are called directly, exactly as a wrapping {@code ActorRef} would call
 * them one at a time.
 */
@DisplayName("JobQueue — priority, dispatch, reservation, drain")
class JobQueuePriorityTest {

    private static Job job(Priority priority, String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                priority, new RecordingResponseSink());
    }

    @Test
    void submit_dispatchesImmediately_whenIdleEndpointWaiting() {
        JobQueue queue = new JobQueue();
        queue.attach("e1");   // empty queue → e1 parks idle

        String endpointId = queue.submit(job(Priority.BACKGROUND, "j1"));

        assertEquals("e1", endpointId);
    }

    @Test
    void submit_fgOvertakesQueuedBg_whenNoIdleEndpoint() {
        JobQueue queue = new JobQueue();
        queue.attach("e1");
        queue.submit(job(Priority.BACKGROUND, "in-flight"));   // e1 no longer idle

        assertNull(queue.submit(job(Priority.BACKGROUND, "b1")));   // queued at back
        assertNull(queue.submit(job(Priority.FOREGROUND, "f1")));   // queued at front

        Job next = queue.requestWork("e1");   // e1 finished "in-flight", asks again

        assertEquals(Priority.FOREGROUND, next.priority());
    }

    @Test
    void reservedEndpoint_skipsBackgroundJobs_untilReservationLapses() {
        JobQueue queue = new JobQueue(Duration.ofMillis(50));
        queue.attach("e1");

        queue.submit(job(Priority.FOREGROUND, "f1"));   // e1 idle → dispatched immediately, e1 now reserved
        queue.requestWork("e1");                        // e1 "finishes" f1 and parks idle again (still reserved)

        // e1 is the only idle endpoint, but it is reserved: a BG submit must not go to it.
        assertNull(queue.submit(job(Priority.BACKGROUND, "b1")));   // queued instead of dispatched

        sleepPastReservation();

        // Reservation lapsed: e1 (still idle, still the only endpoint) takes a BG job immediately.
        assertEquals("e1", queue.submit(job(Priority.BACKGROUND, "b2")));
    }

    private static void sleepPastReservation() {
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void drainPending_returnsAndClearsQueuedJobs() {
        JobQueue queue = new JobQueue();
        queue.submit(job(Priority.BACKGROUND, "b1"));   // no endpoints at all → both queue up
        queue.submit(job(Priority.FOREGROUND, "f1"));

        List<Job> pending = queue.drainPending();
        assertEquals(2, pending.size());

        queue.attach("e1");   // requestWork finds an empty deque → e1 just parks idle
        assertTrue(queue.isIdle());
    }

    @Test
    void isIdle_falseWhileAnEndpointIsProcessing_trueOnceItAsksForMore() {
        JobQueue queue = new JobQueue();
        queue.attach("e1");
        assertTrue(queue.isIdle());   // attached, nothing to do → idle

        queue.submit(job(Priority.BACKGROUND, "b1"));   // dispatched to e1 → e1 no longer idle
        assertFalse(queue.isIdle());

        queue.requestWork("e1");   // e1 finished, asks again, nothing left → parks idle
        assertTrue(queue.isIdle());
    }

    @Test
    void snapshot_reportsActiveIdleAndPendingCounts() {
        JobQueue queue = new JobQueue();
        queue.attach("e1");
        queue.attach("e2");   // both idle: active=0, idle=2, pending=0

        queue.submit(job(Priority.BACKGROUND, "b1"));   // e1 takes it → active=1, idle=1
        queue.submit(job(Priority.BACKGROUND, "b2"));   // e2 takes it → active=2, idle=0
        queue.submit(job(Priority.BACKGROUND, "b3"));   // no idle endpoint → queued

        QueueSnapshot snapshot = queue.snapshot();

        assertEquals(2, snapshot.activeCount());
        assertEquals(0, snapshot.idleCount());
        assertEquals(1, snapshot.pendingCount());
    }

    @Test
    void snapshot_reportsTheActualEndpointIds_notJustCounts() {
        JobQueue queue = new JobQueue();
        queue.attach("192.168.5.16:8000");
        queue.attach("192.168.5.17:8000");
        queue.submit(job(Priority.BACKGROUND, "b1"));   // dispatched to whichever parked idle first

        QueueSnapshot snapshot = queue.snapshot();

        assertEquals(1, snapshot.activeEndpointIds().size());
        assertEquals(1, snapshot.idleEndpointIds().size());
        assertTrue(snapshot.activeEndpointIds().get(0).startsWith("192.168.5."));
        assertTrue(snapshot.idleEndpointIds().get(0).startsWith("192.168.5."));
        assertFalse(snapshot.activeEndpointIds().get(0).equals(snapshot.idleEndpointIds().get(0)));
    }
}
