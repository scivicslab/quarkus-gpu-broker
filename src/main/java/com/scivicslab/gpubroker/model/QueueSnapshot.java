package com.scivicslab.gpubroker.model;

/**
 * A {@code JobQueue}'s current counts, read without mutating anything —
 * the data behind {@code GET /status}.
 */
public record QueueSnapshot(int activeCount, int idleCount, int pendingCount) {
}
