package com.scivicslab.gpubroker.history;

import java.time.Duration;
import java.time.Instant;

import com.scivicslab.gpubroker.model.GenerationMeasurement;

/**
 * The minutes of one ten-minute window, so that the window can say what its busiest minute was
 * ({@code GenerationRateWindowsAndLayout_260923_oo01}).
 *
 * <p>A reply is spread over the minutes it was actually generating in, not charged to the minute
 * it happened to end in. The reply carries {@code decodeMs}, so its generation ran from
 * {@code end - decodeMs} to {@code end}; each minute gets the share of the reply's tokens that
 * matches its share of that span. Charging the whole reply to its last minute would make a burst
 * out of every long reply — a 27B reply of three minutes would read as three minutes' output
 * produced in one.</p>
 *
 * <p>Lives only as long as the window it belongs to. Once the window closes, the two peaks are
 * folded into its {@link GenerationTotals} and the tally is thrown away; nothing per-minute is
 * kept for twenty-four hours.</p>
 *
 * <p>The part of a reply that ran before this window opened is dropped rather than charged to the
 * window before it: that window has closed and its line is already in the history file. The ten
 * minute totals are unaffected — they still count every reply in full, in the window it ended in —
 * so a reply that straddles the boundary makes the busiest-minute figure an understatement, never
 * an overstatement.</p>
 */
final class MinuteTally {

    static final int MINUTES = (int) (StatusHistoryStore.BUCKET_LENGTH.toMinutes());
    private static final long MINUTE_SECONDS = Duration.ofMinutes(1).toSeconds();

    private final Instant windowStart;
    private final long[] tokens = new long[MINUTES];
    private final long[] characters = new long[MINUTES];
    /** Whether the minute's observation found every attached slot generating. */
    private final boolean[] atFullSlots = new boolean[MINUTES];

    MinuteTally(Instant windowStart) {
        this.windowStart = windowStart;
    }

    /** Spreads one finished reply over the minutes its generation actually covered. */
    void add(Instant endedAt, GenerationMeasurement one) {
        if (one.decodeMs() <= 0) {
            // No measured generation span: the reply is a point event in the minute it ended.
            addTo(minuteOf(endedAt), one.tokens(), one.characters());
            return;
        }
        Instant startedAt = endedAt.minusMillis(one.decodeMs());
        for (int minute = 0; minute < MINUTES; minute++) {
            long overlapMs = overlapMs(startedAt, endedAt, minute);
            if (overlapMs <= 0) {
                continue;
            }
            addTo(minute, one.tokens() * overlapMs / one.decodeMs(),
                    one.characters() * overlapMs / one.decodeMs());
        }
    }

    /**
     * How full the queue was, from the minute's own observation. Told once a minute by
     * {@code StatusHistoryStore.record}; a minute nobody observed stays unsaturated, so a peak
     * claimed to be at full slots is never one nothing confirmed.
     */
    void observe(Instant at, int busy, int total) {
        int minute = minuteOf(at);
        if (minute >= 0 && minute < MINUTES && total > 0 && busy >= total) {
            atFullSlots[minute] = true;
        }
    }

    /** The busiest minute's tokens a second, or 0 when nothing was generated in this window. */
    double peakTokensPerSecond() {
        return peak(tokens, false);
    }

    /**
     * The busiest minute among those the observation found full -- what the deployment produces
     * when every slot is working, which is its throughput as a machine rather than its throughput
     * on the traffic it happened to get.
     */
    double peakTokensPerSecondAtFullSlots() {
        return peak(tokens, true);
    }

    /** The busiest minute's characters a second. */
    double peakCharactersPerSecond() {
        return peak(characters, false);
    }

    private void addTo(int minute, long addedTokens, long addedCharacters) {
        if (minute < 0 || minute >= MINUTES) {
            return;
        }
        tokens[minute] += addedTokens;
        characters[minute] += addedCharacters;
    }

    private int minuteOf(Instant at) {
        return (int) Duration.between(windowStart, at).toMinutes();
    }

    private long overlapMs(Instant startedAt, Instant endedAt, int minute) {
        Instant minuteStart = windowStart.plus(Duration.ofMinutes(minute));
        Instant minuteEnd = minuteStart.plus(Duration.ofMinutes(1));
        Instant from = startedAt.isAfter(minuteStart) ? startedAt : minuteStart;
        Instant to = endedAt.isBefore(minuteEnd) ? endedAt : minuteEnd;
        return to.isAfter(from) ? Duration.between(from, to).toMillis() : 0;
    }

    private double peak(long[] perMinute, boolean onlyFullSlots) {
        long best = 0;
        for (int minute = 0; minute < MINUTES; minute++) {
            if (onlyFullSlots && !atFullSlots[minute]) {
                continue;
            }
            best = Math.max(best, perMinute[minute]);
        }
        return (double) best / MINUTE_SECONDS;
    }
}
