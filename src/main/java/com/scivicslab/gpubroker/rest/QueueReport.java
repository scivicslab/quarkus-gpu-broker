package com.scivicslab.gpubroker.rest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.scivicslab.gpubroker.history.EndpointBucket;
import com.scivicslab.gpubroker.history.StatusHistoryStore.QueueHistorySnapshot;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * One queue as {@code GET /queues} reports it — the machine-readable form of what the status
 * page draws, for callers that need to decide whether to send work here.
 *
 * <p>{@code ready} is the field most callers want. {@code GET /v1/models} only tells them the
 * broker itself answers, and it lists chat models alone, so a caller checking whether embedding
 * work can run learns nothing from it. Here, {@code ready} is true when the queue has a slot and
 * at least one of its addresses answered its most recent probe (or, with {@code since} given,
 * answered any probe in the window from {@code since} to now).
 *
 * <p>{@code activeSlots}/{@code idleSlots}/{@code pendingJobs} always describe right now —
 * {@code JobQueue} has no historical variant of its own slot bookkeeping to report instead.
 * {@code since} only widens what {@code endpoints} covers, from one probe window to every closed
 * window (plus the one still filling) at or after it, oldest first, one entry per window per
 * address instead of one entry per address total.
 */
public record QueueReport(String name, int activeSlots, int idleSlots, int totalSlots,
                          int pendingJobs, long completedLastHour, boolean ready,
                          List<EndpointReport> endpoints) {

    public static QueueReport of(QueueStatus status, QueueHistorySnapshot snapshot) {
        return of(status, snapshot, null);
    }

    /**
     * @param since include every probe window at or after this instant, instead of only the most
     *              recent one; {@code null} for the default (most-recent-only) behaviour
     */
    public static QueueReport of(QueueStatus status, QueueHistorySnapshot snapshot, Instant since) {
        QueueSnapshot now = status.snapshot();
        List<EndpointReport> endpoints = new ArrayList<>();
        for (var entry : snapshot.endpointHistories().entrySet()) {
            String address = entry.getKey();
            List<EndpointBucket> observed = entry.getValue();
            if (since == null) {
                endpoints.add(observed.isEmpty()
                        ? EndpointReport.unprobed(address)
                        : EndpointReport.of(observed.get(observed.size() - 1)));
                continue;
            }
            List<EndpointBucket> inRange = observed.stream()
                    .filter(bucket -> !bucket.bucketStart().isBefore(since))
                    .toList();
            if (inRange.isEmpty()) {
                endpoints.add(EndpointReport.unprobed(address));
            } else {
                inRange.forEach(bucket -> endpoints.add(EndpointReport.of(bucket)));
            }
        }
        int total = now.activeCount() + now.idleCount();
        return new QueueReport(status.queueName(), now.activeCount(), now.idleCount(), total,
                now.pendingCount(), snapshot.completedLastHour(),
                isReady(total, endpoints), List.copyOf(endpoints));
    }

    /**
     * A queue with no slot cannot run anything. A queue whose addresses all failed their most
     * recent probe has slots that are registered but not answering — {@code JobQueue} only drops
     * an address when a job happens to fail on it, so the registration outliving the service is
     * the normal case, not an unusual one (see {@code StatusHistory_260905_oo01}).
     *
     * <p>Before the first probe of a freshly started broker, every address is {@code UNKNOWN} and
     * the slot count alone decides — otherwise the broker would report every queue unusable for
     * its first minute.
     */
    private static boolean isReady(int totalSlots, List<EndpointReport> endpoints) {
        if (totalSlots == 0) {
            return false;
        }
        boolean anyProbed = endpoints.stream().anyMatch(e -> e.probeTotal() > 0);
        return anyProbed ? endpoints.stream().anyMatch(EndpointReport::answering) : true;
    }

    /**
     * Every address currently registered in {@code JobQueue} for one queue (active or idle) —
     * what a caller passes as {@code knownAddresses} to {@code StatusHistoryStore.snapshotFor} so
     * an address with slots but no probe history yet (the first minute after startup) still gets
     * reported, not just addresses the probe has already observed.
     */
    public static List<String> registeredAddressesOf(QueueStatus status) {
        SortedSet<String> addresses = new TreeSet<>();
        for (String workerId : status.snapshot().activeEndpointIds()) {
            addresses.add(physicalAddress(workerId));
        }
        for (String workerId : status.snapshot().idleEndpointIds()) {
            addresses.add(physicalAddress(workerId));
        }
        return List.copyOf(addresses);
    }

    /** A worker's actor name is {@code address#slot}; several workers share one address. */
    static String physicalAddress(String workerId) {
        int slot = workerId.lastIndexOf('#');
        return slot < 0 ? workerId : workerId.substring(0, slot);
    }
}
