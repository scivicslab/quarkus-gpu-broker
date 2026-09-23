package com.scivicslab.gpubroker.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.scivicslab.gpubroker.history.GenerationTotals;
import com.scivicslab.gpubroker.history.QueueBucket;
import com.scivicslab.gpubroker.history.StatusHistoryStore;

/**
 * Which of a queue's ten-minute windows a rate may be read from, and what the retained ones peaked
 * at ({@code GenerationRateWindowsAndLayout_260923_oo01}).
 *
 * <p>Every rate on the status page comes from a window that has closed. The window still being
 * filled cannot be divided by anything honest: {@code tokensPerSecondOver} divides by the full ten
 * minutes whatever the elapsed time is, which reads as a tenth of the truth a minute in and climbs
 * to the truth by the end, and dividing by the elapsed time instead would turn the first reply of
 * a window into an enormous number. A closed window is always six hundred seconds.
 */
public final class GenerationWindows {

    /**
     * The peaks worth showing, at three lengths. {@code BestWindow} averages a whole ten minutes
     * and so answers what the queue sustains; {@code BestMinute} is the busiest single minute
     * inside a window and answers what it does in a burst; the per-reply peaks are one reply.
     * They are different kinds of maximum and the page labels each with its own length.
     *
     * <p>The per-reply peak is two figures rather than one because a reply's speed depends on how
     * many others shared the deployment with it. Kept as one number, the faster of the two always
     * wins and the page reports what a lone caller gets as though it were what everyone gets.
     */
    public record Peaks(double tokensPerSecondBestWindow, double tokensPerSecondBestMinute,
                        double tokensPerSecondBestMinuteAtFullSlots,
                        double tokensPerSecondOneReplyAlone,
                        double tokensPerSecondOneReplyAtFullSlots) {

        public static final Peaks NONE = new Peaks(0, 0, 0, 0, 0);

        /** Nothing the deployment as a whole produced has been measured. */
        public boolean noNodeFigure() {
            return tokensPerSecondBestWindow == 0 && tokensPerSecondBestMinute == 0
                    && tokensPerSecondBestMinuteAtFullSlots == 0;
        }

        /** Nothing one caller's own reply achieved has been measured. */
        public boolean noCallerFigure() {
            return tokensPerSecondOneReplyAlone == 0 && tokensPerSecondOneReplyAtFullSlots == 0;
        }
    }

    private GenerationWindows() {
    }

    /**
     * The newest window that has closed, or null when the broker has not been up for a whole one.
     *
     * <p>Selected by start instant rather than by position, because the list's last element is not
     * reliably the open window: a queue with no activity this window has no open bucket at all, and
     * a restart inside a window leaves the open one merged into the last closed one.
     *
     * @param buckets a queue's history, oldest first, as {@code StatusHistoryStore} hands it over
     * @param now     the instant the page is being rendered for
     */
    public static QueueBucket lastClosed(List<QueueBucket> buckets, Instant now) {
        Instant openStart = StatusHistoryStore.floorToBucket(now);
        for (int i = buckets.size() - 1; i >= 0; i--) {
            if (buckets.get(i).bucketStart().isBefore(openStart)) {
                return buckets.get(i);
            }
        }
        return null;
    }

    /**
     * The highest each rate reached across the closed windows still retained -- twenty-four hours
     * of them.
     *
     * <p>Over the same windows the displayed figure is read from, so that a number shown as the
     * peak is one the row above could have shown. The window being filled is left out: its
     * throughput figure is understated until it closes, and including it would mean the peak and
     * the current value were measured differently.
     */
    public static Peaks peaks(List<QueueBucket> buckets, Instant now) {
        Instant openStart = StatusHistoryStore.floorToBucket(now);
        double bestWindow = 0;
        double bestMinute = 0;
        double bestMinuteFull = 0;
        double alone = 0;
        double atFullSlots = 0;
        for (QueueBucket bucket : buckets) {
            if (!bucket.bucketStart().isBefore(openStart)) {
                continue;
            }
            GenerationTotals totals = bucket.generated();
            bestWindow = Math.max(bestWindow,
                    totals.tokensPerSecondOver(StatusHistoryStore.BUCKET_LENGTH));
            bestMinute = Math.max(bestMinute, totals.maxTokensPerSecondOneMinute());
            bestMinuteFull = Math.max(bestMinuteFull, totals.maxTokensPerSecondOneMinuteAtFullSlots());
            alone = Math.max(alone, totals.maxTokensPerSecondOneReplyAlone());
            atFullSlots = Math.max(atFullSlots, totals.maxTokensPerSecondOneReplyAtFullSlots());
        }
        return new Peaks(bestWindow, bestMinute, bestMinuteFull, alone, atFullSlots);
    }

    /** The window a closed bucket covers, for the callers that divide by it. */
    public static Duration length() {
        return StatusHistoryStore.BUCKET_LENGTH;
    }
}
