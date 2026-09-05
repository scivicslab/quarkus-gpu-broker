package com.scivicslab.gpubroker.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * The scheduling behaviour {@code gpu-broker} exists to provide, written as the
 * four steps it is specified in ({@code ForegroundSlotHandover_260905_oo01}):
 *
 * <ol>
 *   <li>With no FOREGROUND job in sight, every slot runs BACKGROUND work — no
 *       slot is held empty "just in case".</li>
 *   <li>A FOREGROUND job arriving while all slots are busy goes to the front of
 *       the deque and takes the first slot that frees up, waiting for exactly one
 *       BACKGROUND job rather than for the backlog.</li>
 *   <li>That slot is then reserved: it stays idle instead of picking up the next
 *       BACKGROUND job, even while thousands are queued.</li>
 *   <li>The next FOREGROUND job runs on it immediately.</li>
 * </ol>
 *
 * <p>Four slots and a large BACKGROUND backlog throughout, so that "immediately"
 * is only possible if step 3 actually held a slot back.
 */
@DisplayName("JobQueue — a slot is handed from BACKGROUND to FOREGROUND and held for the next turn")
class ForegroundSlotHandoverTest {

    private static final int SLOTS = 4;
    private static final int BACKLOG = 10_000;

    private static Job job(Priority priority, String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                priority, new RecordingResponseSink());
    }

    /**
     * Fills every slot with BACKGROUND work and leaves a backlog queued, which is the state the
     * broker is in after hours of batch traffic and no interactive use.
     */
    private static JobQueue queueSaturatedWithBackground(List<String> busySlots) {
        JobQueue queue = new JobQueue();
        for (int slot = 0; slot < SLOTS; slot++) {
            queue.attach("e" + slot);
        }
        for (int i = 0; i < BACKLOG; i++) {
            String endpointId = queue.submit(job(Priority.BACKGROUND, "bg" + i));
            if (endpointId != null) {
                busySlots.add(endpointId);
            }
        }
        return queue;
    }

    /** Step 1: no slot is held empty while only BACKGROUND work exists. */
    @Test
    void withoutAnyForegroundTraffic_everySlotRunsBackground() {
        List<String> busySlots = new ArrayList<>();
        JobQueue queue = queueSaturatedWithBackground(busySlots);

        assertEquals(SLOTS, busySlots.size());
        assertEquals(0, queue.snapshot().idleCount());
        assertEquals(SLOTS, queue.snapshot().activeCount());
    }

    /** Step 2: a FOREGROUND job waits for one BACKGROUND job, not for the backlog. */
    @Test
    void foregroundArrivingWhileBusy_takesTheFirstSlotThatFrees() {
        List<String> busySlots = new ArrayList<>();
        JobQueue queue = queueSaturatedWithBackground(busySlots);

        assertNull(queue.submit(job(Priority.FOREGROUND, "fg1")), "all slots busy, so it queues");

        // The first slot to finish asks for work and must be handed the FOREGROUND job,
        // ahead of the 9,999 BACKGROUND jobs queued before it.
        Job handed = queue.requestWork(busySlots.get(0));

        assertNotNull(handed);
        assertEquals(Priority.FOREGROUND, handed.priority());
    }

    /** Step 3: after the FOREGROUND job, that slot stays idle instead of taking more BACKGROUND. */
    @Test
    void afterServingForeground_thatSlotStaysIdleDespiteTheBacklog() {
        List<String> busySlots = new ArrayList<>();
        JobQueue queue = queueSaturatedWithBackground(busySlots);
        queue.submit(job(Priority.FOREGROUND, "fg1"));
        String servingSlot = busySlots.get(0);
        queue.requestWork(servingSlot);          // takes fg1

        Job next = queue.requestWork(servingSlot);   // fg1 done, asks for the next job

        assertNull(next, "reserved, so it must not take a BACKGROUND job");
        assertEquals(List.of(servingSlot), queue.snapshot().idleEndpointIds());
    }

    /** Step 4: the next FOREGROUND job runs at once, on the slot held back in step 3. */
    @Test
    void nextForegroundJob_runsImmediatelyOnTheHeldSlot() {
        List<String> busySlots = new ArrayList<>();
        JobQueue queue = queueSaturatedWithBackground(busySlots);
        queue.submit(job(Priority.FOREGROUND, "fg1"));
        String servingSlot = busySlots.get(0);
        queue.requestWork(servingSlot);
        queue.requestWork(servingSlot);          // fg1 done; slot now idle and reserved

        String dispatchedTo = queue.submit(job(Priority.FOREGROUND, "fg2"));

        assertEquals(servingSlot, dispatchedTo, "dispatched at once, not queued");
        assertEquals(BACKLOG - SLOTS, queue.snapshot().pendingCount(),
                "the deque is unchanged, so fg2 was not left waiting behind the backlog");
    }

    /**
     * The same four steps with the reservation switched off, to show that steps 3 and 4 are
     * carried by the reservation and not by something else in the queue: the slot that just
     * served FOREGROUND immediately takes BACKGROUND again, and the next FOREGROUND job finds
     * no idle slot and has to queue.
     */
    @Test
    void withoutTheReservation_theSlotIsLostToBackgroundAndTheNextTurnQueues() {
        List<String> busySlots = new ArrayList<>();
        JobQueue queue = new JobQueue(Duration.ZERO);   // package-visible constructor: no reservation
        for (int slot = 0; slot < SLOTS; slot++) {
            queue.attach("e" + slot);
        }
        for (int i = 0; i < BACKLOG; i++) {
            String endpointId = queue.submit(job(Priority.BACKGROUND, "bg" + i));
            if (endpointId != null) {
                busySlots.add(endpointId);
            }
        }
        queue.submit(job(Priority.FOREGROUND, "fg1"));
        String servingSlot = busySlots.get(0);
        queue.requestWork(servingSlot);                 // takes fg1

        Job next = queue.requestWork(servingSlot);      // step 3 without a reservation

        assertNotNull(next, "no reservation, so the slot takes BACKGROUND straight away");
        assertEquals(Priority.BACKGROUND, next.priority());
        assertEquals(0, queue.snapshot().idleCount());
        assertNull(queue.submit(job(Priority.FOREGROUND, "fg2")), "step 4 fails: nothing is idle");
    }

    /** The held slot is protected from BACKGROUND arriving through submit as well as requestWork. */
    @Test
    void whileHeld_backgroundSubmittedLater_doesNotTakeTheSlot() {
        List<String> busySlots = new ArrayList<>();
        JobQueue queue = queueSaturatedWithBackground(busySlots);
        queue.submit(job(Priority.FOREGROUND, "fg1"));
        String servingSlot = busySlots.get(0);
        queue.requestWork(servingSlot);
        queue.requestWork(servingSlot);

        assertNull(queue.submit(job(Priority.BACKGROUND, "bg-late")));
        assertTrue(queue.snapshot().idleEndpointIds().contains(servingSlot));
    }
}
