package com.scivicslab.gpubroker.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

@Tag("S_history")
@DisplayName("StatusHistoryStore — ten-minute buckets, 24h retention, restart-surviving history")
class StatusHistoryStoreTest {

    private static final Instant NOON = Instant.parse("2026-09-05T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void observationsWithinOneBucket_accumulateIntoASingleBucket() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 2, 1, 5, 0, 0)));
        store.record(NOON.plus(Duration.ofMinutes(3)), List.of(), List.of(status("q", 4, 3, 7, 0, 0)));

        List<QueueBucket> history = store.queueHistory("q");
        assertEquals(1, history.size());
        assertEquals(2, history.get(0).sampleCount());
        assertEquals(3.0, history.get(0).activeAverage());
        assertEquals(6.0, history.get(0).pendingAverage());
    }

    @Test
    void crossingATenMinuteBoundary_closesTheBucketAndOpensTheNext() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 1, 0, 0, 0, 0)));
        store.record(NOON.plus(Duration.ofMinutes(11)), List.of(), List.of(status("q", 9, 0, 0, 0, 0)));

        List<QueueBucket> history = store.queueHistory("q");
        assertEquals(2, history.size());
        assertEquals(Instant.parse("2026-09-05T12:00:00Z"), history.get(0).bucketStart());
        assertEquals(Instant.parse("2026-09-05T12:10:00Z"), history.get(1).bucketStart());
    }

    @Test
    void completedTotal_isStoredAsThePerBucketIncrement() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 0, 0, 0, 100, 0)));
        store.record(NOON.plus(Duration.ofMinutes(1)), List.of(), List.of(status("q", 0, 0, 0, 130, 0)));
        store.record(NOON.plus(Duration.ofMinutes(2)), List.of(), List.of(status("q", 0, 0, 0, 145, 0)));

        // The first observation only establishes the baseline; 100 jobs finished before it.
        assertEquals(45, store.queueHistory("q").get(0).completed());
    }

    /** The counters live in JobQueue's memory, so a restart takes them back to zero. */
    @Test
    void cumulativeTotalGoingBackwards_countsZeroRatherThanANegativeIncrement() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 0, 0, 0, 500, 0)));
        store.record(NOON.plus(Duration.ofMinutes(1)), List.of(), List.of(status("q", 0, 0, 0, 3, 0)));
        store.record(NOON.plus(Duration.ofMinutes(2)), List.of(), List.of(status("q", 0, 0, 0, 8, 0)));

        assertEquals(5, store.queueHistory("q").get(0).completed());
    }

    @Test
    void bucketsOlderThanTwentyFourHours_areDropped() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 1, 0, 0, 0, 0)));
        store.record(NOON.plus(Duration.ofHours(25)), List.of(), List.of(status("q", 2, 0, 0, 0, 0)));

        List<QueueBucket> history = store.queueHistory("q");
        assertEquals(1, history.size());
        assertEquals(Instant.parse("2026-09-06T13:00:00Z"), history.get(0).bucketStart());
    }

    @Test
    void probeResults_becomeUpPartialOrDownForTheBucket() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, probes(true), List.of());
        store.record(NOON.plus(Duration.ofMinutes(1)), probes(false), List.of());

        assertEquals(EndpointBucket.Health.PARTIAL, store.endpointHistory("10.0.0.1:8000").get(0).health());
    }

    @Test
    void anAddressThatAnsweredEveryProbe_isUp() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, probes(true), List.of());
        store.record(NOON.plus(Duration.ofMinutes(1)), probes(true), List.of());

        assertEquals(EndpointBucket.Health.UP, store.endpointHistory("10.0.0.1:8000").get(0).health());
    }

    @Test
    void anAddressThatAnsweredNothing_isDown() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, probes(false), List.of());

        assertEquals(EndpointBucket.Health.DOWN, store.endpointHistory("10.0.0.1:8000").get(0).health());
    }

    @Test
    void closedBucketsAreWrittenToFileAndReadBackByANewStore() {
        Path file = directory.resolve("history.jsonl");
        StatusHistoryStore first = new StatusHistoryStore(file);
        first.record(NOON, probes(true), List.of(status("q", 2, 1, 5, 0, 0)));
        first.record(NOON.plus(Duration.ofMinutes(11)), probes(true), List.of(status("q", 3, 0, 0, 0, 0)));

        StatusHistoryStore restarted = new StatusHistoryStore(file);
        restarted.load(NOON.plus(Duration.ofMinutes(12)));

        List<QueueBucket> history = restarted.queueHistory("q");
        assertEquals(1, history.size());
        assertEquals(Instant.parse("2026-09-05T12:00:00Z"), history.get(0).bucketStart());
        assertEquals(2.0, history.get(0).activeAverage());
        assertEquals(EndpointBucket.Health.UP, restarted.endpointHistory("10.0.0.1:8000").get(0).health());
    }

    @Test
    void loadingPrunesLinesOlderThanTheRetentionWindowFromTheFile() throws IOException {
        Path file = directory.resolve("history.jsonl");
        StatusHistoryStore first = new StatusHistoryStore(file);
        first.record(NOON, List.of(), List.of(status("q", 1, 0, 0, 0, 0)));
        first.record(NOON.plus(Duration.ofMinutes(11)), List.of(), List.of(status("q", 2, 0, 0, 0, 0)));
        first.flush();

        new StatusHistoryStore(file).load(NOON.plus(Duration.ofHours(24).plusMinutes(5)));

        String remaining = Files.readString(file);
        assertFalse(remaining.contains("2026-09-05T12:00:00Z"));
        assertTrue(remaining.contains("2026-09-05T12:10:00Z"));
    }

    @Test
    void flush_closesTheBucketStillBeingFilled() {
        Path file = directory.resolve("history.jsonl");
        StatusHistoryStore store = new StatusHistoryStore(file);
        store.record(NOON, List.of(), List.of(status("q", 1, 0, 0, 0, 0)));

        store.flush();

        StatusHistoryStore restarted = new StatusHistoryStore(file);
        restarted.load(NOON.plus(Duration.ofMinutes(1)));
        assertEquals(1, restarted.queueHistory("q").size());
    }

    @Test
    void completedLastHour_sumsTheLastSixBuckets() {
        StatusHistoryStore store = new StatusHistoryStore(null);
        long total = 0;
        for (int minute = 0; minute <= 100; minute += 10) {
            total += 10;
            store.record(NOON.plus(Duration.ofMinutes(minute)), List.of(), List.of(status("q", 0, 0, 0, total, 0)));
        }

        // Eleven buckets exist, each holding ten completions; only the last six count.
        assertEquals(60, store.completedLastHour("q"));
    }

    @Test
    void addressesOfAQueue_areListedOnce() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(new ProbeObservation("10.0.0.1:8000", "q", true),
                new ProbeObservation("10.0.0.2:8000", "q", false)), List.of());
        store.record(NOON.plus(Duration.ofMinutes(1)), probes(true), List.of());

        assertEquals(List.of("10.0.0.1:8000", "10.0.0.2:8000"), store.addressesOf("q"));
    }


    /**
     * Restarting inside a ten-minute window leaves two rows for that window: the stopping
     * instance flushes its partial bucket and the starting instance opens its own. Restored
     * separately they would draw as two columns at the same instant, shifting everything after.
     */
    @Test
    void twoRowsForTheSameWindow_areMergedIntoOneBucketOnLoad() {
        Path file = directory.resolve("history.jsonl");
        StatusHistoryStore first = new StatusHistoryStore(file);
        first.record(NOON, probes(true), List.of(status("q", 2, 0, 4, 0, 0)));
        first.flush();                                    // the stopping instance

        StatusHistoryStore second = new StatusHistoryStore(file);
        second.record(NOON.plus(Duration.ofMinutes(1)), probes(true), List.of(status("q", 4, 0, 8, 0, 0)));
        second.flush();                                   // the starting instance, same window

        StatusHistoryStore restarted = new StatusHistoryStore(file);
        restarted.load(NOON.plus(Duration.ofMinutes(2)));

        List<QueueBucket> history = restarted.queueHistory("q");
        assertEquals(1, history.size(), "one window, one bucket");
        assertEquals(2, history.get(0).sampleCount());
        assertEquals(3.0, history.get(0).activeAverage());
        assertEquals(6.0, history.get(0).pendingAverage());
        assertEquals(1, restarted.endpointHistory("10.0.0.1:8000").size());
        assertEquals(2, restarted.endpointHistory("10.0.0.1:8000").get(0).probeTotal());
    }

    @Test
    void utilizationIsBusySlotsOverAttachedSlots() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 3, 1, 0, 0, 0)));

        assertEquals(4.0, store.queueHistory("q").get(0).totalSlotsAverage());
        assertEquals(75.0, store.queueHistory("q").get(0).utilizationPercent());
    }

    @Test
    void utilizationIsZero_whenTheQueueHasNoSlot() {
        StatusHistoryStore store = new StatusHistoryStore(null);

        store.record(NOON, List.of(), List.of(status("q", 0, 0, 5, 0, 0)));

        assertEquals(0.0, store.queueHistory("q").get(0).utilizationPercent());
    }

    private static List<ProbeObservation> probes(boolean responded) {
        return List.of(new ProbeObservation("10.0.0.1:8000", "q", responded));
    }

    private static QueueStatus status(String queueName, int active, int idle, int pending,
                                      long completedTotal, long failedTotal) {
        return new QueueStatus(queueName, new QueueSnapshot(
                activeIds(active), idleIds(idle), pending, completedTotal, failedTotal));
    }

    private static List<String> activeIds(int count) {
        return ids("10.0.0.1:8000#", count, 0);
    }

    private static List<String> idleIds(int count) {
        return ids("10.0.0.2:8000#", count, 0);
    }

    private static List<String> ids(String prefix, int count, int from) {
        return java.util.stream.IntStream.range(from, from + count).mapToObj(i -> prefix + i).toList();
    }
}
