package com.scivicslab.gpubroker.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.scivicslab.gpubroker.history.EndpointBucket;
import com.scivicslab.gpubroker.history.StatusHistoryStore;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * One queue as {@code GET /queues} reports it — the machine-readable form of what the status
 * page draws, for callers that need to decide whether to send work here.
 *
 * <p>{@code ready} is the field most callers want. {@code GET /v1/models} only tells them the
 * broker itself answers, and it lists chat models alone, so a caller checking whether embedding
 * work can run learns nothing from it. Here, {@code ready} is true when the queue has a slot and
 * at least one of its addresses answered its most recent probe.
 */
public record QueueReport(String name, int activeSlots, int idleSlots, int totalSlots,
                          int pendingJobs, long completedLastHour, boolean ready,
                          List<EndpointReport> endpoints) {

    public static QueueReport of(QueueStatus status, StatusHistoryStore history) {
        QueueSnapshot snapshot = status.snapshot();
        List<EndpointReport> endpoints = new ArrayList<>();
        for (String address : addressesOf(status, history)) {
            List<EndpointBucket> observed = history.endpointHistory(address);
            endpoints.add(observed.isEmpty()
                    ? EndpointReport.unprobed(address)
                    : EndpointReport.of(observed.get(observed.size() - 1)));
        }
        int total = snapshot.activeCount() + snapshot.idleCount();
        return new QueueReport(status.queueName(), snapshot.activeCount(), snapshot.idleCount(), total,
                snapshot.pendingCount(), history.completedLastHour(status.queueName()),
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
     * Every address this queue should report: those the probe has observed, plus those currently
     * registered in {@code JobQueue}. The second source matters in the first minute after
     * startup, before any probe has run. The status page draws its liveness rows from the same
     * list, so both surfaces name the same addresses.
     */
    static List<String> addressesOf(QueueStatus status, StatusHistoryStore history) {
        SortedSet<String> addresses = new TreeSet<>(history.addressesOf(status.queueName()));
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
