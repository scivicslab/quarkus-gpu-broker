package com.scivicslab.gpubroker.model;

import java.util.List;

/**
 * A {@code JobQueue}'s current state, read without mutating anything — the
 * data behind the status page. {@code activeEndpointIds}/{@code
 * idleEndpointIds} are the actual {@code AiServiceEndpoint} addresses
 * (their actor name), not just counts, so the page can show which physical
 * node is serving a queue.
 *
 * <p>{@code completedTotal}/{@code failedTotal} are cumulative since this
 * {@code JobQueue} was created, not per-interval counts: a snapshot is a
 * reading, and turning successive readings into a rate is the caller's job.
 * {@code StatusHistoryStore} keeps the previous reading and stores the
 * difference — see {@code StatusHistory_260905_oo01}.
 */
public record QueueSnapshot(List<String> activeEndpointIds, List<String> idleEndpointIds, int pendingCount,
                            long completedTotal, long failedTotal) {

    public int activeCount() {
        return activeEndpointIds.size();
    }

    public int idleCount() {
        return idleEndpointIds.size();
    }
}
