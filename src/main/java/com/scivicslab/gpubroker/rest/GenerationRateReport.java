package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.history.GenerationTotals;
import com.scivicslab.gpubroker.history.StatusHistoryStore;

/**
 * How fast replies came back over one ten-minute window, as {@code GET /queues} reports it
 * ({@code GenerationRateOnTheStatusPage_260915_oo01}).
 *
 * <p>Two rates, because they answer different questions. {@code tokensPerSecondPerReply} is what
 * one person waiting experiences, and falls as the same machine takes on more at once.
 * {@code tokensPerSecond} is what the machine or the queue produced per second of wall clock, and
 * rises with concurrency until it stops rising -- which is where the capacity is.
 *
 * <p>{@code meanQueuedMs} is the part no vLLM server can report: its own clock starts when the
 * request reaches it. A queue with one slot spends its time here, not in generation.
 *
 * <p>Comparable for one model over time, and between machines running that same model. Not
 * comparable between models: the tokenizers differ, so the same sentence is a different number of
 * tokens.
 */
public record GenerationRateReport(long replies, long tokens, double tokensPerSecond,
                                   double tokensPerSecondPerReply, long meanQueuedMs,
                                   long meanFirstTokenMs) {

    public static final GenerationRateReport NONE = new GenerationRateReport(0, 0, 0, 0, 0, 0);

    public static GenerationRateReport of(GenerationTotals totals) {
        return new GenerationRateReport(totals.generations(), totals.tokens(),
                round(totals.tokensPerSecondOver(StatusHistoryStore.BUCKET_LENGTH)),
                round(totals.tokensPerSecondPerReply()),
                totals.meanQueuedMs(), totals.meanFirstMs());
    }

    /** One decimal place: the measurement is an event count, and more digits would claim precision. */
    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
