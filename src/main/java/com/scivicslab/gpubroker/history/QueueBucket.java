package com.scivicslab.gpubroker.history;

import java.time.Instant;

/**
 * One queue's aggregate over one ten-minute bucket — the unit the status
 * page's history charts are drawn from, and the unit written to the history
 * file (see {@code StatusHistory_260905_oo01}).
 *
 * <p>{@code activeSum}/{@code idleSum}/{@code pendingSum} are sums over
 * {@code sampleCount} one-minute observations, not averages: keeping the sum
 * and the count lets a bucket with missing observations (the broker was
 * restarting) average correctly instead of being read as a dip to zero.
 *
 * <p>{@code completed}/{@code failed} are per-bucket counts, already
 * differenced from {@code QueueSnapshot}'s cumulative totals by {@link
 * StatusHistoryStore}.
 */
public record QueueBucket(String queueName, Instant bucketStart, int sampleCount,
                          long activeSum, long idleSum, long pendingSum,
                          long completed, long failed) {

    /** An empty bucket to start accumulating into. */
    public static QueueBucket empty(String queueName, Instant bucketStart) {
        return new QueueBucket(queueName, bucketStart, 0, 0, 0, 0, 0, 0);
    }

    /** This bucket plus one more one-minute observation. */
    public QueueBucket plusSample(int active, int idle, int pending, long completedDelta, long failedDelta) {
        return new QueueBucket(queueName, bucketStart, sampleCount + 1,
                activeSum + active, idleSum + idle, pendingSum + pending,
                completed + completedDelta, failed + failedDelta);
    }

    /**
     * Two records of the same ten-minute window, added together. Restarting inside a window
     * leaves one row written by the stopping instance and one by the starting instance; without
     * this they would be restored as two buckets at the same instant and drawn as two columns.
     */
    public QueueBucket mergedWith(QueueBucket other) {
        if (!bucketStart.equals(other.bucketStart()) || !queueName.equals(other.queueName())) {
            throw new IllegalArgumentException("only the same queue's same bucket can be merged");
        }
        return new QueueBucket(queueName, bucketStart, sampleCount + other.sampleCount(),
                activeSum + other.activeSum(), idleSum + other.idleSum(), pendingSum + other.pendingSum(),
                completed + other.completed(), failed + other.failed());
    }

    /** Slots attached to the queue during this bucket: busy ones plus free ones. */
    public double totalSlotsAverage() {
        return activeAverage() + idleAverage();
    }

    /** Busy slots as a percentage of attached slots — 0 when the queue had no slot at all. */
    public double utilizationPercent() {
        double total = totalSlotsAverage();
        return total == 0.0 ? 0.0 : 100.0 * activeAverage() / total;
    }

    public double activeAverage() {
        return sampleCount == 0 ? 0.0 : (double) activeSum / sampleCount;
    }

    public double idleAverage() {
        return sampleCount == 0 ? 0.0 : (double) idleSum / sampleCount;
    }

    public double pendingAverage() {
        return sampleCount == 0 ? 0.0 : (double) pendingSum / sampleCount;
    }
}
