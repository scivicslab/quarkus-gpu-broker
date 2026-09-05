package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.history.EndpointBucket;

/**
 * One address's liveness as {@code GET /queues} reports it: what the probe found in the
 * ten-minute window still being filled, or in the last completed one if this window has no
 * observation yet.
 *
 * <p>{@code health} is the name of an {@link EndpointBucket.Health} value ({@code UP},
 * {@code PARTIAL}, {@code DOWN}) rather than a boolean, because "answered some of the last ten
 * probes" is a state a caller may want to treat differently from "answered none".
 */
public record EndpointReport(String address, String health, int probeOk, int probeTotal) {

    /** An address the broker knows about but has never probed — no observation to report yet. */
    public static EndpointReport unprobed(String address) {
        return new EndpointReport(address, "UNKNOWN", 0, 0);
    }

    public static EndpointReport of(EndpointBucket bucket) {
        return new EndpointReport(bucket.address(), bucket.health().name(), bucket.probeOk(), bucket.probeTotal());
    }

    /** Whether this address answered at least one of the probes in its most recent window. */
    public boolean answering() {
        return probeOk > 0;
    }
}
