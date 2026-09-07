package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.history.EndpointBucket;

/**
 * One address's liveness as {@code GET /queues} reports it: what the probe found in one
 * ten-minute window — the one still being filled by default, or one from further back when the
 * caller asked for {@code since}.
 *
 * <p>{@code health} is the name of an {@link EndpointBucket.Health} value ({@code UP},
 * {@code PARTIAL}, {@code DOWN}) rather than a boolean, because "answered some of the last ten
 * probes" is a state a caller may want to treat differently from "answered none".
 *
 * <p>{@code bucketStart} is the ISO-8601 instant the window began, or {@code null} for an address
 * with no observation at all — never absent for an address that has been probed, so a caller can
 * tell how stale (or how far in the past) one report is without a second request.
 */
public record EndpointReport(String address, String health, int probeOk, int probeTotal, String bucketStart) {

    /** An address the broker knows about but has never probed — no observation to report yet. */
    public static EndpointReport unprobed(String address) {
        return new EndpointReport(address, "UNKNOWN", 0, 0, null);
    }

    public static EndpointReport of(EndpointBucket bucket) {
        return new EndpointReport(bucket.address(), bucket.health().name(), bucket.probeOk(), bucket.probeTotal(),
                bucket.bucketStart().toString());
    }

    /** Whether this address answered at least one of the probes in its window. */
    public boolean answering() {
        return probeOk > 0;
    }
}
