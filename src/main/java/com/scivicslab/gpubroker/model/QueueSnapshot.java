package com.scivicslab.gpubroker.model;

import java.util.List;

/**
 * A {@code JobQueue}'s current state, read without mutating anything — the
 * data behind the status page. {@code activeEndpointIds}/{@code
 * idleEndpointIds} are the actual {@code AiServiceEndpoint} addresses
 * (their actor name), not just counts, so the page can show which physical
 * node is serving a queue.
 */
public record QueueSnapshot(List<String> activeEndpointIds, List<String> idleEndpointIds, int pendingCount) {

    public int activeCount() {
        return activeEndpointIds.size();
    }

    public int idleCount() {
        return idleEndpointIds.size();
    }
}
