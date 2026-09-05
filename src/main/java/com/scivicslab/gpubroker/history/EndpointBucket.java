package com.scivicslab.gpubroker.history;

import java.time.Instant;

/**
 * One {@code AiServiceEndpoint} address's liveness over one ten-minute
 * bucket: how many of that bucket's probes the address answered.
 *
 * <p>Liveness is a measured thing here, unlike {@code QueueSnapshot}'s
 * active/idle lists, which only say whether {@code JobQueue} still has the
 * address registered — see {@code StatusHistory_260905_oo01} "なぜ死活を
 * JobQueue の登録簿から読まないか".
 */
public record EndpointBucket(String address, String queueName, Instant bucketStart, int probeOk, int probeTotal) {

    /** How this bucket is painted on the liveness band. */
    public enum Health {
        /** Answered every probe in the bucket. */
        UP,
        /** Answered some but not all — a flapping or overloaded endpoint. */
        PARTIAL,
        /** Answered no probe in the bucket. */
        DOWN
    }

    public static EndpointBucket empty(String address, String queueName, Instant bucketStart) {
        return new EndpointBucket(address, queueName, bucketStart, 0, 0);
    }

    /** This bucket plus one more probe result. */
    public EndpointBucket plusProbe(boolean responded) {
        return new EndpointBucket(address, queueName, bucketStart, probeOk + (responded ? 1 : 0), probeTotal + 1);
    }

    /** Two records of the same ten-minute window, added together — see {@code QueueBucket.mergedWith}. */
    public EndpointBucket mergedWith(EndpointBucket other) {
        if (!bucketStart.equals(other.bucketStart()) || !address.equals(other.address())) {
            throw new IllegalArgumentException("only the same address's same bucket can be merged");
        }
        return new EndpointBucket(address, queueName, bucketStart,
                probeOk + other.probeOk(), probeTotal + other.probeTotal());
    }

    public Health health() {
        if (probeTotal == 0 || probeOk == 0) {
            return Health.DOWN;
        }
        return probeOk == probeTotal ? Health.UP : Health.PARTIAL;
    }
}
