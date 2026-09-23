package com.scivicslab.gpubroker.history;

import java.time.Duration;

import com.scivicslab.gpubroker.model.GenerationMeasurement;

/**
 * What the generations in one bucket added up to ({@code GenerationRateOnTheStatusPage_260915_oo01}).
 *
 * <p>Sums rather than rates, because the two rates worth knowing are different divisions of the
 * same sums. The two maxima are the exception: a bucket's fastest single reply cannot be
 * recovered from sums, and it is kept here rather than as one running maximum so that
 * {@code StatusHistoryStore}'s pruning drops a peak once it leaves the retention window
 * ({@code GenerationRateWindowsAndLayout_260923_oo01}). {@code tokens / decodeMs} is how fast one request was answered, which is what a person
 * waiting feels. {@code tokens / the window's length} is what the machine produced per second,
 * which is what a machine running sixty-four at once is worth. Keeping the sums lets a bucket
 * answer both, and lets two buckets be added.</p>
 *
 * @param generations how many replies ended in this bucket
 * @param tokens      events that carried generated text, summed
 * @param decodeMs    time from first generated text to last, summed
 * @param queuedMs    time spent waiting for a slot, summed
 * @param firstMs     time from being handed to a worker to the first generated text, summed
 * @param characters  characters of generated text, summed
 * @param maxTokensPerSecondOneReplyAlone the fastest reply in this bucket that had the queue to
 *                                        itself, in tokens a second of its own generation time
 * @param maxTokensPerSecondOneReplyAtFullSlots the fastest reply that ran with every slot
 *                                        generating -- the speed one caller gets under load,
 *                                        which is a different quantity from the one above
 * @param maxCharactersPerSecondOneReplyAlone the solo reply speed in characters a second
 * @param maxTokensPerSecondOneMinute     the busiest single minute of this window, in tokens a
 *                                        second; attached by {@link MinuteTally} as the window
 *                                        closes, and 0 for a window still being filled
 * @param maxTokensPerSecondOneMinuteAtFullSlots the busiest minute among those the observation
 *                                        found with every slot generating -- the deployment's
 *                                        throughput as a machine, rather than its throughput on
 *                                        whatever traffic it happened to get
 * @param maxCharactersPerSecondOneMinute the same busiest minute in characters a second
 */
public record GenerationTotals(long generations, long tokens, long decodeMs,
                               long queuedMs, long firstMs, long characters,
                               double maxTokensPerSecondOneReplyAlone,
                               double maxTokensPerSecondOneReplyAtFullSlots,
                               double maxCharactersPerSecondOneReplyAlone,
                               double maxTokensPerSecondOneMinute,
                               double maxTokensPerSecondOneMinuteAtFullSlots,
                               double maxCharactersPerSecondOneMinute) {

    public static final GenerationTotals NONE =
            new GenerationTotals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** This bucket plus one reply that has just ended. */
    public GenerationTotals plus(GenerationMeasurement one) {
        double oneTokens = one.decodeMs() == 0 ? 0 : one.tokens() * 1000.0 / one.decodeMs();
        double oneChars = one.decodeMs() == 0 ? 0 : one.characters() * 1000.0 / one.decodeMs();
        // A reply whose occupancy changed while it ran belongs to neither peak: it is counted in
        // the sums and left out of both, rather than filed under whichever it started as.
        return new GenerationTotals(generations + 1, tokens + one.tokens(), decodeMs + one.decodeMs(),
                queuedMs + one.queuedMs(), firstMs + one.firstMs(), characters + one.characters(),
                one.ranAlone() ? Math.max(maxTokensPerSecondOneReplyAlone, oneTokens)
                        : maxTokensPerSecondOneReplyAlone,
                one.ranAtFullSlots() ? Math.max(maxTokensPerSecondOneReplyAtFullSlots, oneTokens)
                        : maxTokensPerSecondOneReplyAtFullSlots,
                one.ranAlone() ? Math.max(maxCharactersPerSecondOneReplyAlone, oneChars)
                        : maxCharactersPerSecondOneReplyAlone,
                maxTokensPerSecondOneMinute, maxTokensPerSecondOneMinuteAtFullSlots,
                maxCharactersPerSecondOneMinute);
    }

    /** Two records of the same window, added together. */
    public GenerationTotals plus(GenerationTotals other) {
        return new GenerationTotals(generations + other.generations(), tokens + other.tokens(),
                decodeMs + other.decodeMs(), queuedMs + other.queuedMs(), firstMs + other.firstMs(),
                characters + other.characters(),
                Math.max(maxTokensPerSecondOneReplyAlone, other.maxTokensPerSecondOneReplyAlone()),
                Math.max(maxTokensPerSecondOneReplyAtFullSlots, other.maxTokensPerSecondOneReplyAtFullSlots()),
                Math.max(maxCharactersPerSecondOneReplyAlone, other.maxCharactersPerSecondOneReplyAlone()),
                Math.max(maxTokensPerSecondOneMinute, other.maxTokensPerSecondOneMinute()),
                Math.max(maxTokensPerSecondOneMinuteAtFullSlots,
                        other.maxTokensPerSecondOneMinuteAtFullSlots()),
                Math.max(maxCharactersPerSecondOneMinute, other.maxCharactersPerSecondOneMinute()));
    }

    /**
     * This window's sums, with the busiest minute attached. Called once, as the window closes:
     * a window still being filled has minutes that have not happened yet, and a peak over them
     * would climb as they did.
     */
    public GenerationTotals withMinutePeaks(double tokensPerSecond, double tokensPerSecondAtFullSlots,
                                           double charactersPerSecond) {
        return new GenerationTotals(generations, tokens, decodeMs, queuedMs, firstMs, characters,
                maxTokensPerSecondOneReplyAlone, maxTokensPerSecondOneReplyAtFullSlots,
                maxCharactersPerSecondOneReplyAlone, tokensPerSecond, tokensPerSecondAtFullSlots,
                charactersPerSecond);
    }

    /**
     * How fast one reply arrived, on average: tokens per second of actual generation.
     *
     * <p>Falls as the same machine takes on more at once, and is not a capacity figure for that
     * reason -- read it next to {@link #tokensPerSecondOver}.</p>
     */
    public double tokensPerSecondPerReply() {
        return decodeMs == 0 ? 0 : tokens * 1000.0 / decodeMs;
    }

    /**
     * How much was produced per second of wall clock -- the throughput of whatever this bucket
     * belongs to, one machine or one queue of them.
     *
     * @param window the bucket's length
     */
    public double tokensPerSecondOver(Duration window) {
        long seconds = window.toSeconds();
        return seconds == 0 ? 0 : (double) tokens / seconds;
    }

    /**
     * How fast one reply arrived in characters a second -- the same measurement as
     * {@link #tokensPerSecondPerReply} in a unit that does not depend on the tokenizer, and so the
     * one to put two models side by side with.
     */
    public double charactersPerSecondPerReply() {
        return decodeMs == 0 ? 0 : characters * 1000.0 / decodeMs;
    }

    /** Characters produced per second of wall clock, by whatever this bucket belongs to. */
    public double charactersPerSecondOver(Duration window) {
        long seconds = window.toSeconds();
        return seconds == 0 ? 0 : (double) characters / seconds;
    }

    /** Mean wait for a slot. The figure that explains a queue of one slot. */
    public long meanQueuedMs() {
        return generations == 0 ? 0 : queuedMs / generations;
    }

    /** Mean time from reaching a server to its first generated text. */
    public long meanFirstMs() {
        return generations == 0 ? 0 : firstMs / generations;
    }
}
