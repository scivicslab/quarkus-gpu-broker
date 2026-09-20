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
public record EndpointReport(String address, String health, int probeOk, int probeTotal, String bucketStart,
                             GenerationRateReport generated) {

    /** An address the broker knows about but has never probed — no observation to report yet. */
    public static EndpointReport unprobed(String address) {
        return new EndpointReport(address, "UNKNOWN", 0, 0, null, GenerationRateReport.NONE);
    }

    /**
     * One address as it reads now: the last thing observed about it, whether that was the minute's
     * probe or a job it just ran ({@code LivenessFromWorkNotOnlyProbes_260920_oo01}). Distinct from
     * {@link #of(EndpointBucket)}, which says what a ten-minute window looked like.
     */
    public static EndpointReport now(com.scivicslab.gpubroker.history.Liveness latest, EndpointBucket bucket) {
        return new EndpointReport(latest.address(), latest.health().name(),
                bucket == null ? 0 : bucket.probeOk(), bucket == null ? 0 : bucket.probeTotal(),
                latest.at().toString(),
                GenerationRateReport.of(bucket == null ? com.scivicslab.gpubroker.history.GenerationTotals.NONE
                        : bucket.generated()));
    }

    public static EndpointReport of(EndpointBucket bucket) {
        return new EndpointReport(bucket.address(), bucket.health().name(), bucket.probeOk(), bucket.probeTotal(),
                bucket.bucketStart().toString(), GenerationRateReport.of(bucket.generated()));
    }

    /** Whether this address answered at least one of the probes in its window. */
    public boolean answering() {
        return probeOk > 0;
    }
}
