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
public record EndpointBucket(String address, String queueName, Instant bucketStart,
                             int probeOk, int probeTotal,
                             int workOk, int workFailed, GenerationTotals generated) {

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
        return new EndpointBucket(address, queueName, bucketStart, 0, 0, 0, 0, GenerationTotals.NONE);
    }

    /** This bucket plus one more probe result. */
    public EndpointBucket plusProbe(boolean responded) {
        return new EndpointBucket(address, queueName, bucketStart,
                probeOk + (responded ? 1 : 0), probeTotal + 1, workOk, workFailed, generated);
    }

    /** This bucket plus one reply that has just ended on this address. */
    public EndpointBucket plusGeneration(com.scivicslab.gpubroker.model.GenerationMeasurement one) {
        return new EndpointBucket(address, queueName, bucketStart, probeOk, probeTotal,
                workOk, workFailed, generated.plus(one));
    }

    /** This bucket as it closes, carrying the busiest minute its window saw. */
    public EndpointBucket withMinutePeaks(double tokensPerSecond, double tokensPerSecondAtFullSlots,
                                          double charactersPerSecond) {
        return new EndpointBucket(address, queueName, bucketStart, probeOk, probeTotal,
                workOk, workFailed, generated.withMinutePeaks(tokensPerSecond, tokensPerSecondAtFullSlots, charactersPerSecond));
    }

    /**
     * This bucket plus one job this address actually ran.
     *
     * <p>A probe asks whether the address answers; this is whether it did the work. YomiToku
     * answered {@code GET /} with 200 for weeks while every {@code POST /ocr/markdown} returned a
     * CUDA error, and the band said UP throughout
     * ({@code LivenessFromWorkNotOnlyProbes_260920_oo01}).</p>
     */
    public EndpointBucket plusWork(boolean ok) {
        return new EndpointBucket(address, queueName, bucketStart, probeOk, probeTotal,
                workOk + (ok ? 1 : 0), workFailed + (ok ? 0 : 1), generated);
    }

    /** Two records of the same ten-minute window, added together — see {@code QueueBucket.mergedWith}. */
    public EndpointBucket mergedWith(EndpointBucket other) {
        if (!bucketStart.equals(other.bucketStart()) || !address.equals(other.address())) {
            throw new IllegalArgumentException("only the same address's same bucket can be merged");
        }
        return new EndpointBucket(address, queueName, bucketStart,
                probeOk + other.probeOk(), probeTotal + other.probeTotal(),
                workOk + other.workOk(), workFailed + other.workFailed(),
                generated.plus(other.generated()));
    }

    /**
     * How this address looked over this window, from both what it answered and what it ran.
     *
     * <p>A job that failed on it is the strongest evidence there is: it is what the probe was
     * standing in for. One such failure keeps the window off UP however many probes passed.</p>
     */
    public Health health() {
        boolean answered = probeTotal > 0 && probeOk > 0;
        if (!answered && workOk == 0) {
            return Health.DOWN;
        }
        boolean everyProbePassed = probeTotal == 0 || probeOk == probeTotal;
        return everyProbePassed && workFailed == 0 ? Health.UP : Health.PARTIAL;
    }
}
