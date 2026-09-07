package com.scivicslab.gpubroker.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * Folds one-minute observations into ten-minute buckets, keeps the last 24
 * hours of them, and writes each closed bucket to a file so the history
 * survives a restart — the data behind the status page's history charts
 * (see {@code StatusHistory_260905_oo01}).
 *
 * <p>Not an actor. The only mutation path is {@link #record}, called by
 * {@code StatusHistoryRecorder}'s single scheduled thread; the status page
 * reads concurrently on a request thread. That is one writer and N readers
 * over a handful of maps, which {@code synchronized} covers without
 * introducing a mailbox — an actor here would buy serialization that is
 * already guaranteed by there being exactly one caller of {@link #record}.
 */
public class StatusHistoryStore {

    private static final Logger LOG = Logger.getLogger(StatusHistoryStore.class.getName());

    static final Duration BUCKET_LENGTH = Duration.ofMinutes(10);
    static final Duration RETENTION = Duration.ofHours(24);
    /** Buckets shown on the history charts: 24 hours at ten minutes each. */
    public static final int BUCKETS_PER_DAY = (int) (RETENTION.toMinutes() / BUCKET_LENGTH.toMinutes());

    private final Path historyFile;
    private final ObjectMapper json = new ObjectMapper();

    private final Map<String, QueueBucket> openQueueBuckets = new LinkedHashMap<>();
    private final Map<String, EndpointBucket> openEndpointBuckets = new LinkedHashMap<>();
    private final Map<String, Deque<QueueBucket>> closedQueueBuckets = new LinkedHashMap<>();
    private final Map<String, Deque<EndpointBucket>> closedEndpointBuckets = new LinkedHashMap<>();
    /** queueName -> {completedTotal, failedTotal} as of the previous observation. */
    private final Map<String, long[]> previousTotals = new HashMap<>();

    private Instant openBucketStart;

    public StatusHistoryStore(Path historyFile) {
        this.historyFile = historyFile;
    }

    /** Reads the file back, keeping only buckets inside the retention window, and prunes the file to match. */
    public synchronized void load(Instant now) {
        if (historyFile == null || !Files.exists(historyFile)) {
            return;
        }
        List<String> kept = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (restore(line, now)) {
                    kept.add(line);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "failed to read status history file " + historyFile + "; starting with empty history", e);
            return;
        }
        sortRestoredBuckets();
        rewrite(kept);
    }

    /**
     * Folds one round of observations into the open bucket, closing the previous one first if
     * {@code now} has crossed a ten-minute boundary.
     */
    public synchronized void record(Instant now, List<ProbeObservation> probes, List<QueueStatus> statuses) {
        Instant bucketStart = floorToBucket(now);
        if (openBucketStart == null) {
            openBucketStart = bucketStart;
        } else if (!bucketStart.equals(openBucketStart)) {
            closeOpenBuckets();
            openBucketStart = bucketStart;
        }
        for (QueueStatus status : statuses) {
            accumulateQueue(status);
        }
        for (ProbeObservation probe : probes) {
            accumulateEndpoint(probe);
        }
        pruneClosed(now);
    }

    private void accumulateQueue(QueueStatus status) {
        long[] previous = previousTotals.get(status.queueName());
        long completedDelta = 0;
        long failedDelta = 0;
        if (previous != null) {
            // A smaller total than last time means the broker restarted and JobQueue's counters
            // went back to zero. Count nothing for this observation and take the current value as
            // the new baseline — see StatusHistory_260905_oo01.
            completedDelta = Math.max(0, status.snapshot().completedTotal() - previous[0]);
            failedDelta = Math.max(0, status.snapshot().failedTotal() - previous[1]);
        }
        previousTotals.put(status.queueName(),
                new long[] {status.snapshot().completedTotal(), status.snapshot().failedTotal()});

        QueueBucket open = openQueueBuckets.computeIfAbsent(status.queueName(),
                name -> QueueBucket.empty(name, openBucketStart));
        openQueueBuckets.put(status.queueName(), open.plusSample(
                status.snapshot().activeCount(), status.snapshot().idleCount(), status.snapshot().pendingCount(),
                completedDelta, failedDelta));
    }

    private void accumulateEndpoint(ProbeObservation probe) {
        EndpointBucket open = openEndpointBuckets.computeIfAbsent(probe.address(),
                address -> EndpointBucket.empty(address, probe.queueName(), openBucketStart));
        openEndpointBuckets.put(probe.address(), open.plusProbe(probe.responded()));
    }

    private void closeOpenBuckets() {
        List<String> lines = new ArrayList<>();
        for (QueueBucket bucket : openQueueBuckets.values()) {
            closedQueueBuckets.computeIfAbsent(bucket.queueName(), n -> new ArrayDeque<>()).addLast(bucket);
            lines.add(toLine(bucket));
        }
        for (EndpointBucket bucket : openEndpointBuckets.values()) {
            closedEndpointBuckets.computeIfAbsent(bucket.address(), a -> new ArrayDeque<>()).addLast(bucket);
            lines.add(toLine(bucket));
        }
        openQueueBuckets.clear();
        openEndpointBuckets.clear();
        append(lines);
    }

    /** Closes whatever is still open — called at shutdown so the last partial bucket is not lost. */
    public synchronized void flush() {
        if (openQueueBuckets.isEmpty() && openEndpointBuckets.isEmpty()) {
            return;
        }
        closeOpenBuckets();
    }

    /**
     * Closed buckets for one queue, oldest first, plus the bucket still being filled.
     *
     * <p>Restarting inside a ten-minute window can leave the open bucket sharing its
     * {@code bucketStart} with the last closed one: {@link #load} restored a window as already
     * closed (merged from the previous instance's own restart-time flush — see
     * {@code twoRowsForTheSameWindow_areMergedIntoOneBucketOnLoad}), and then this instance's
     * first {@link #record} for that same still-current window opened a fresh bucket rather than
     * reopening the restored one. Left unmerged, that one window would report as two — exactly
     * what {@code load} already takes care to avoid for two rows read from the file, so the same
     * care is needed here for a closed-then-reopened pair.</p>
     */
    public synchronized List<QueueBucket> queueHistory(String queueName) {
        List<QueueBucket> history = new ArrayList<>(closedQueueBuckets.getOrDefault(queueName, new ArrayDeque<>()));
        QueueBucket open = openQueueBuckets.get(queueName);
        if (open == null) {
            return history;
        }
        if (!history.isEmpty() && history.get(history.size() - 1).bucketStart().equals(open.bucketStart())) {
            history.set(history.size() - 1, history.get(history.size() - 1).mergedWith(open));
        } else {
            history.add(open);
        }
        return history;
    }

    /** Every address ever observed for one queue, in a stable order. */
    public synchronized List<String> addressesOf(String queueName) {
        TreeSet<String> addresses = new TreeSet<>();
        for (Deque<EndpointBucket> buckets : closedEndpointBuckets.values()) {
            EndpointBucket last = buckets.peekLast();
            if (last != null && queueName.equals(last.queueName())) {
                addresses.add(last.address());
            }
        }
        for (EndpointBucket open : openEndpointBuckets.values()) {
            if (queueName.equals(open.queueName())) {
                addresses.add(open.address());
            }
        }
        return List.copyOf(addresses);
    }

    /**
     * Closed liveness buckets for one address, oldest first, plus the bucket still being filled.
     *
     * <p>Merges the open bucket into the last closed one when they share a {@code bucketStart} —
     * see {@link #queueHistory} for why this happens and why it must be merged, not appended.</p>
     */
    public synchronized List<EndpointBucket> endpointHistory(String address) {
        List<EndpointBucket> history = new ArrayList<>(closedEndpointBuckets.getOrDefault(address, new ArrayDeque<>()));
        EndpointBucket open = openEndpointBuckets.get(address);
        if (open == null) {
            return history;
        }
        if (!history.isEmpty() && history.get(history.size() - 1).bucketStart().equals(open.bucketStart())) {
            history.set(history.size() - 1, history.get(history.size() - 1).mergedWith(open));
        } else {
            history.add(open);
        }
        return history;
    }

    /**
     * Jobs finished in the last hour for one queue — the denominator of the estimated wait
     * (see {@code StatusHistory_260905_oo01} "なぜ推定待ち時間の分母を直近1時間にするか").
     */
    public synchronized long completedLastHour(String queueName) {
        List<QueueBucket> history = queueHistory(queueName);
        int bucketsPerHour = (int) (Duration.ofHours(1).toMinutes() / BUCKET_LENGTH.toMinutes());
        long total = 0;
        for (int i = Math.max(0, history.size() - bucketsPerHour); i < history.size(); i++) {
            total += history.get(i).completed();
        }
        return total;
    }

    static Instant floorToBucket(Instant now) {
        long bucketSeconds = BUCKET_LENGTH.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), bucketSeconds) * bucketSeconds);
    }

    private void pruneClosed(Instant now) {
        Instant oldest = now.minus(RETENTION);
        closedQueueBuckets.values().forEach(buckets -> {
            while (!buckets.isEmpty() && buckets.peekFirst().bucketStart().isBefore(oldest)) {
                buckets.pollFirst();
            }
        });
        closedEndpointBuckets.values().forEach(buckets -> {
            while (!buckets.isEmpty() && buckets.peekFirst().bucketStart().isBefore(oldest)) {
                buckets.pollFirst();
            }
        });
    }

    private void sortRestoredBuckets() {
        closedQueueBuckets.replaceAll((name, buckets) -> {
            List<QueueBucket> sorted = new ArrayList<>(buckets);
            sorted.sort(Comparator.comparing(QueueBucket::bucketStart));
            return new ArrayDeque<>(sorted);
        });
        closedEndpointBuckets.replaceAll((address, buckets) -> {
            List<EndpointBucket> sorted = new ArrayList<>(buckets);
            sorted.sort(Comparator.comparing(EndpointBucket::bucketStart));
            return new ArrayDeque<>(sorted);
        });
    }

    /** Restores one line; returns whether it is inside the retention window and was kept. */
    private boolean restore(String line, Instant now) {
        JsonNode node;
        try {
            node = json.readTree(line);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "skipping unreadable status history line", e);
            return false;
        }
        Instant bucketStart = Instant.parse(node.path("bucket").asText());
        if (bucketStart.isBefore(now.minus(RETENTION))) {
            return false;
        }
        if ("queue".equals(node.path("kind").asText())) {
            QueueBucket restored = new QueueBucket(node.path("queue").asText(), bucketStart,
                    node.path("samples").asInt(), node.path("activeSum").asLong(),
                    node.path("idleSum").asLong(), node.path("pendingSum").asLong(),
                    node.path("completed").asLong(), node.path("failed").asLong());
            Deque<QueueBucket> buckets = closedQueueBuckets.computeIfAbsent(restored.queueName(), n -> new ArrayDeque<>());
            QueueBucket sameWindow = removeSameWindow(buckets, restored.bucketStart(), QueueBucket::bucketStart);
            buckets.addLast(sameWindow == null ? restored : sameWindow.mergedWith(restored));
        } else {
            EndpointBucket restored = new EndpointBucket(node.path("address").asText(), node.path("queue").asText(),
                    bucketStart, node.path("probeOk").asInt(), node.path("probeTotal").asInt());
            Deque<EndpointBucket> buckets = closedEndpointBuckets.computeIfAbsent(restored.address(), a -> new ArrayDeque<>());
            EndpointBucket sameWindow = removeSameWindow(buckets, restored.bucketStart(), EndpointBucket::bucketStart);
            buckets.addLast(sameWindow == null ? restored : sameWindow.mergedWith(restored));
        }
        return true;
    }

    /**
     * Takes out the bucket already restored for {@code bucketStart}, if the file holds a second
     * row for the same ten-minute window. Restarting inside a window produces exactly that: the
     * stopping instance flushes its partial bucket and the starting instance opens its own.
     */
    private static <T> T removeSameWindow(Deque<T> buckets, Instant bucketStart,
                                          java.util.function.Function<T, Instant> startOf) {
        for (Iterator<T> it = buckets.iterator(); it.hasNext(); ) {
            T bucket = it.next();
            if (startOf.apply(bucket).equals(bucketStart)) {
                it.remove();
                return bucket;
            }
        }
        return null;
    }

    private String toLine(QueueBucket bucket) {
        ObjectNode node = json.createObjectNode();
        node.put("kind", "queue");
        node.put("bucket", bucket.bucketStart().toString());
        node.put("queue", bucket.queueName());
        node.put("samples", bucket.sampleCount());
        node.put("activeSum", bucket.activeSum());
        node.put("idleSum", bucket.idleSum());
        node.put("pendingSum", bucket.pendingSum());
        node.put("completed", bucket.completed());
        node.put("failed", bucket.failed());
        return node.toString();
    }

    private String toLine(EndpointBucket bucket) {
        ObjectNode node = json.createObjectNode();
        node.put("kind", "endpoint");
        node.put("bucket", bucket.bucketStart().toString());
        node.put("address", bucket.address());
        node.put("queue", bucket.queueName());
        node.put("probeOk", bucket.probeOk());
        node.put("probeTotal", bucket.probeTotal());
        return node.toString();
    }

    private void append(List<String> lines) {
        if (historyFile == null || lines.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(historyFile.getParent());
            Files.write(historyFile, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Losing history must not stop the broker from brokering — see StatusHistory_260905_oo01.
            LOG.log(Level.SEVERE, "failed to append to status history file " + historyFile, e);
        }
    }

    private void rewrite(List<String> lines) {
        if (historyFile == null) {
            return;
        }
        Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(historyFile.getParent());
            Files.write(temporary, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Same reason as append: the in-memory history is already correct, and an
            // un-pruned file only costs disk — see StatusHistory_260905_oo01.
            LOG.log(Level.SEVERE, "failed to prune status history file " + historyFile, e);
        }
    }
}
