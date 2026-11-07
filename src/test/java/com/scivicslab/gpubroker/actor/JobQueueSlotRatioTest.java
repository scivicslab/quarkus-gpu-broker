package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * Tests of {@code foreground-background-slot-ratio}: one FOREGROUND job counts as that many slots
 * of its address, one BACKGROUND job as one, an idle slot reserved for FOREGROUND work as one, and
 * a job runs only if the load of its address stays within the address's slots. The slots are named
 * {@code host:port#N}, as {@code AiServiceEndpoint} names them; the reservation is long enough not
 * to lapse during a test.
 */
@DisplayName("JobQueue — foreground-to-background slot ratio per address")
class JobQueueSlotRatioTest {

    private static final Duration LONG = Duration.ofHours(1);

    private static Job job(Priority priority, String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                priority, new RecordingResponseSink());
    }

    /** A queue whose address {@code a:1} has {@code slots} slots and the given ratio. */
    private static JobQueue queue(int slots, double ratio) {
        JobQueue queue = new JobQueue(LONG, address -> address.equals("a:1") ? ratio : 1);
        for (int i = 0; i < slots; i++) {
            queue.attach("a:1#" + i);
        }
        return queue;
    }

    @Test
    void foregroundAlone_runsUpToTheSlotsOverTheRatio() {
        JobQueue queue = queue(4, 2);

        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f1")));
        assertEquals("a:1#1", queue.submit(job(Priority.FOREGROUND, "f2")));
        assertNull(queue.submit(job(Priority.FOREGROUND, "f3")));   // two slots idle, but 3 x 2 > 4
    }

    @Test
    void backgroundAlone_runsOnEverySlot() {
        JobQueue queue = queue(4, 2);

        for (int i = 0; i < 4; i++) {
            assertEquals("a:1#" + i, queue.submit(job(Priority.BACKGROUND, "b" + i)));
        }
    }

    @Test
    void aMix_staysWithinTheSlots() {
        JobQueue queue = queue(4, 2);

        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f1")));   // load 2
        assertEquals("a:1#1", queue.submit(job(Priority.BACKGROUND, "b1")));   // load 3
        assertEquals("a:1#2", queue.submit(job(Priority.BACKGROUND, "b2")));   // load 4
        assertNull(queue.submit(job(Priority.FOREGROUND, "f2")));              // a:1#3 idle, 4 + 2 > 4
    }

    @Test
    void whileForegroundWaits_backgroundDoesNotTakeItsRoom_andTheRoomBackgroundLeavesGoesToIt() {
        JobQueue queue = queue(6, 2);
        for (int i = 0; i < 5; i++) {
            queue.submit(job(Priority.BACKGROUND, "b" + i));   // a:1#0..4, load 5
        }
        Job f1 = job(Priority.FOREGROUND, "f1");
        assertNull(queue.submit(f1));                                   // a:1#5 idle, 5 + 2 > 6
        assertNull(queue.submit(job(Priority.BACKGROUND, "late")));     // would leave no room for f1

        // b0 finishes on a:1#0: load 4, and f1 fits there before the late background job.
        assertSame(f1, queue.requestWork("a:1#0"));
    }

    @Test
    void aReservedIdleSlot_countsAsOne() {
        JobQueue queue = queue(4, 2);
        queue.submit(job(Priority.FOREGROUND, "f1"));   // a:1#0
        assertNull(queue.requestWork("a:1#0"));         // done: idle, reserved, load 1

        assertEquals("a:1#1", queue.submit(job(Priority.BACKGROUND, "b1")));
        assertEquals("a:1#2", queue.submit(job(Priority.BACKGROUND, "b2")));
        assertEquals("a:1#3", queue.submit(job(Priority.BACKGROUND, "b3")));   // load 4: 1 + 3
    }

    @Test
    void aFractionalRatio_isCountedAsWritten() {
        JobQueue queue = queue(3, 1.5);

        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f1")));   // load 1.5
        assertEquals("a:1#1", queue.submit(job(Priority.FOREGROUND, "f2")));   // load 3
        assertNull(queue.submit(job(Priority.BACKGROUND, "b1")));              // a:1#2 idle, 3 + 1 > 3
    }

    @Test
    void noRatio_isTheBehaviourOfBefore() {
        JobQueue queue = new JobQueue(LONG);
        for (int i = 0; i < 4; i++) {
            queue.attach("a:1#" + i);
        }
        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f0")));
        assertEquals("a:1#1", queue.submit(job(Priority.BACKGROUND, "b1")));
        assertEquals("a:1#2", queue.submit(job(Priority.FOREGROUND, "f2")));
        assertEquals("a:1#3", queue.submit(job(Priority.FOREGROUND, "f3")));
    }

    @Test
    void theRatioOfOneAddress_doesNotHoldBackAnother() {
        Map<String, Double> ratios = Map.of("a:1", 2.0);
        JobQueue queue = new JobQueue(LONG, address -> ratios.getOrDefault(address, 1.0));
        queue.attach("a:1#0");
        queue.attach("a:1#1");
        queue.attach("b:2#0");

        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f1")));   // a:1 load 2 of 2
        assertEquals("b:2#0", queue.submit(job(Priority.FOREGROUND, "f2")));   // a:1#1 is idle but full
    }

    @Test
    void addressOf_takesTheHostAndPortOfASlotName() {
        assertEquals("172.19.17.182:8000", JobQueue.addressOf("172.19.17.182:8000#12"));
        assertEquals("e1", JobQueue.addressOf("e1"));
    }
}
