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
 * Tests of the cap on the slots of one address that FOREGROUND work may hold
 * ({@code foreground-max-concurrency}): a server sized for a few long conversations takes many
 * more short background jobs. The slots are named {@code host:port#N}, as {@code
 * AiServiceEndpoint} names them; the reservation is long enough not to lapse during a test.
 */
@DisplayName("JobQueue — foreground cap per address")
class JobQueueForegroundCapTest {

    private static final Duration LONG = Duration.ofHours(1);

    private static Job job(Priority priority, String label) {
        return Job.first(new RequestBody(label.getBytes(StandardCharsets.UTF_8), "text/plain"),
                priority, new RecordingResponseSink());
    }

    /** A queue whose address {@code a:1} has four slots and a foreground cap of {@code cap}. */
    private static JobQueue fourSlots(int cap) {
        JobQueue queue = new JobQueue(LONG, address -> address.equals("a:1") ? cap : Integer.MAX_VALUE);
        for (int i = 0; i < 4; i++) {
            queue.attach("a:1#" + i);
        }
        return queue;
    }

    @Test
    void foregroundWaits_whenItsAddressHoldsTheCap() {
        JobQueue queue = fourSlots(2);

        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f1")));
        assertEquals("a:1#1", queue.submit(job(Priority.FOREGROUND, "f2")));
        assertNull(queue.submit(job(Priority.FOREGROUND, "f3")));   // two slots free, but the cap is 2
    }

    @Test
    void backgroundRuns_whileForegroundWaitsAtTheCap() {
        JobQueue queue = fourSlots(2);
        queue.submit(job(Priority.FOREGROUND, "f1"));
        queue.submit(job(Priority.FOREGROUND, "f2"));
        queue.submit(job(Priority.FOREGROUND, "f3"));   // waits at the front

        assertEquals("a:1#2", queue.submit(job(Priority.BACKGROUND, "b1")));
        assertEquals("a:1#3", queue.submit(job(Priority.BACKGROUND, "b2")));
    }

    @Test
    void aSlotForegroundFrees_goesToTheWaitingForegroundJob() {
        JobQueue queue = fourSlots(2);
        queue.submit(job(Priority.FOREGROUND, "f1"));
        queue.submit(job(Priority.FOREGROUND, "f2"));
        Job f3 = job(Priority.FOREGROUND, "f3");
        queue.submit(f3);
        queue.submit(job(Priority.BACKGROUND, "b1"));   // on a:1#2
        Job b2 = job(Priority.BACKGROUND, "b2");
        queue.submit(job(Priority.BACKGROUND, "b-on-3"));   // on a:1#3
        queue.submit(b2);                                   // waits behind f3

        // a:1#0 finishes f1: it is still reserved for foreground work, so f3 takes it.
        assertSame(f3, queue.requestWork("a:1#0"));
    }

    @Test
    void aSlotBackgroundFrees_goesToBackground_whileForegroundIsAtTheCap() {
        JobQueue queue = fourSlots(2);
        queue.submit(job(Priority.FOREGROUND, "f1"));
        queue.submit(job(Priority.FOREGROUND, "f2"));
        queue.submit(job(Priority.FOREGROUND, "f3"));   // waits
        queue.submit(job(Priority.BACKGROUND, "b1"));   // on a:1#2
        queue.submit(job(Priority.BACKGROUND, "b-on-3"));
        Job b2 = job(Priority.BACKGROUND, "b2");
        queue.submit(b2);

        // a:1#2 finishes b1: a:1 still holds two foreground slots, so f3 may not take it; b2 does.
        assertSame(b2, queue.requestWork("a:1#2"));
    }

    @Test
    void aReservedIdleSlot_countsAgainstTheCap_andMayBeTakenAgain() {
        JobQueue queue = fourSlots(1);
        queue.submit(job(Priority.FOREGROUND, "f1"));   // on a:1#0
        assertNull(queue.requestWork("a:1#0"));         // done; idle but reserved

        // The cap is held by the reserved slot: only that slot may take the next turn.
        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f2")));
        assertNull(queue.submit(job(Priority.FOREGROUND, "f3")));
    }

    @Test
    void noCap_isTheBehaviourOfBefore() {
        JobQueue queue = new JobQueue(LONG);
        for (int i = 0; i < 4; i++) {
            queue.attach("a:1#" + i);
        }
        for (int i = 0; i < 4; i++) {
            assertEquals("a:1#" + i, queue.submit(job(Priority.FOREGROUND, "f" + i)));
        }
    }

    @Test
    void theCapOfOneAddress_doesNotHoldBackAnother() {
        Map<String, Integer> caps = Map.of("a:1", 1);
        JobQueue queue = new JobQueue(LONG, address -> caps.getOrDefault(address, Integer.MAX_VALUE));
        queue.attach("a:1#0");
        queue.attach("a:1#1");
        queue.attach("b:2#0");

        assertEquals("a:1#0", queue.submit(job(Priority.FOREGROUND, "f1")));
        assertEquals("b:2#0", queue.submit(job(Priority.FOREGROUND, "f2")));   // a:1#1 is held back
    }

    @Test
    void addressOf_takesTheHostAndPortOfASlotName() {
        assertEquals("172.19.17.182:8000", JobQueue.addressOf("172.19.17.182:8000#12"));
        assertEquals("e1", JobQueue.addressOf("e1"));
    }
}
