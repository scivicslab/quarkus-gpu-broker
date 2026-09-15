package com.scivicslab.gpubroker.history;

import java.time.Duration;

import com.scivicslab.gpubroker.model.GenerationMeasurement;

/**
 * What the generations in one bucket added up to ({@code GenerationRateOnTheStatusPage_260915_oo01}).
 *
 * <p>Sums rather than rates, because the two rates worth knowing are different divisions of the
 * same sums. {@code tokens / decodeMs} is how fast one request was answered, which is what a person
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
 */
public record GenerationTotals(long generations, long tokens, long decodeMs,
                               long queuedMs, long firstMs, long characters) {

    public static final GenerationTotals NONE = new GenerationTotals(0, 0, 0, 0, 0, 0);

    /** This bucket plus one reply that has just ended. */
    public GenerationTotals plus(GenerationMeasurement one) {
        return new GenerationTotals(generations + 1, tokens + one.tokens(), decodeMs + one.decodeMs(),
                queuedMs + one.queuedMs(), firstMs + one.firstMs(), characters + one.characters());
    }

    /** Two records of the same window, added together. */
    public GenerationTotals plus(GenerationTotals other) {
        return new GenerationTotals(generations + other.generations(), tokens + other.tokens(),
                decodeMs + other.decodeMs(), queuedMs + other.queuedMs(), firstMs + other.firstMs(),
                characters + other.characters());
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
