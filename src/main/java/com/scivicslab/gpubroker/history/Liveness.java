package com.scivicslab.gpubroker.history;

import java.time.Instant;

/**
 * The most recent thing observed about one address, and what observed it
 * ({@code LivenessFromWorkNotOnlyProbes_260920_oo01}).
 *
 * <p>The status page mixes three time scales, and this is the finest of them: what is true now.
 * The ten-minute buckets are what a window looked like, and the day of buckets is the band. A
 * reading taken every minute does not belong in the same figure as one covering ten.</p>
 *
 * @param address   the {@code host:port} observed
 * @param queueName the queue it serves
 * @param answered  whether it answered
 * @param source    what observed it
 * @param at        when
 */
public record Liveness(String address, String queueName, boolean answered, Source source, Instant at) {

    /** Where an observation came from. */
    public enum Source {
        /** The minute's {@code GET /}: the address is reachable and of the expected kind. */
        PROBE,
        /** A job actually run on it. The thing a probe stands in for. */
        WORK
    }

    /** How this address reads right now. A failed job is DOWN at once, not at the next probe. */
    public EndpointBucket.Health health() {
        return answered ? EndpointBucket.Health.UP : EndpointBucket.Health.DOWN;
    }
}
