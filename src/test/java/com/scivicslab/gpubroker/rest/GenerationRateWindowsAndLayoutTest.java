package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.history.GenerationTotals;
import com.scivicslab.gpubroker.history.StatusHistoryStore;
import com.scivicslab.gpubroker.history.StatusHistoryStore.QueueHistorySnapshot;
import com.scivicslab.gpubroker.model.GenerationMeasurement;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * Which window a rate is read from, what the retained windows peaked at, and the card header that
 * says so. See {@code GenerationRateWindowsAndLayout_260923_oo01}.
 */
@Tag("GenerationRateWindowsAndLayout_260923_oo01")
@DisplayName("速さの数字は、閉じた窓から読み、窓の名前を名乗る")
class GenerationRateWindowsAndLayoutTest {

    private static final String QUEUE = "vllm-gemma4";
    /** 12:05 — five minutes into the window that opened at 12:00. */
    private static final Instant INSIDE_SECOND_WINDOW = Instant.parse("2026-09-23T12:05:00Z");
    private static final Instant FIRST_WINDOW = Instant.parse("2026-09-23T11:52:00Z");

    private static final QueueStatus STATUS = new QueueStatus(QUEUE,
            new QueueSnapshot(List.of("192.168.5.16:8000#0"), List.of("192.168.5.16:8000#1"), 0, 0, 0));

    @Test
    @DisplayName("窓が1つも閉じていなければ、速さの行を出さない")
    void render_beforeAnyWindowHasClosed_omitsTheRateRow() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.recordGeneration(INSIDE_SECOND_WINDOW, measurement(600, 10_000, 3_000));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertFalse(html.contains("last 10 min"),
                "the only window with replies in it is still being filled");
    }

    @Test
    @DisplayName("速さは、埋まりかけの窓ではなく直前の閉じた窓から来る")
    void render_readsTheClosedWindowNotTheOneStillFilling() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // 600 tokens in the window that has closed -> 1.0 tok/s over its 600 seconds.
        history.recordGeneration(FIRST_WINDOW, measurement(600, 10_000, 1_800));
        // 6,000 in the one still filling; reading it would show 10.0 instead.
        history.recordGeneration(INSIDE_SECOND_WINDOW, measurement(6_000, 10_000, 18_000));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("tok/s <b>1.0</b> <small>total</small>"),
                "the closed window's 600 tokens over 600 seconds");
        assertFalse(html.contains("<b>10.0</b> <small>total</small>"),
                "the window still filling must not be divided by a length it has not reached");
    }

    @Test
    @DisplayName("閉じた窓に返答が無ければ、0.0 ではなくダッシュを出す")
    void render_closedWindowWithNoReply_showsADashNotZero() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // A probe-only observation opens and then closes a window carrying no generation.
        history.record(FIRST_WINDOW, List.of(), List.of(STATUS));
        history.record(INSIDE_SECOND_WINDOW, List.of(), List.of(STATUS));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("tok/s <b>&mdash;</b> <small>total</small>"),
                "nothing to measure is not a measured zero");
    }

    @Test
    @DisplayName("見出しは、いま・直前の10分・24時間の最大の3行に分かれる")
    void render_namesTheWindowOfEveryRowOfFigures() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.recordGeneration(FIRST_WINDOW, measurement(600, 10_000, 1_800));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("<span class=\"window\">now</span>"));
        assertTrue(html.contains("<span class=\"window\">last 10 min</span>"));
        assertTrue(html.contains("<span class=\"window\">24 h peak — node</span>"));
        assertTrue(html.contains("<span class=\"window\">24 h peak — one caller</span>"));
        assertEquals(4, countOccurrences(html, "class=\"figurerow\""),
                "now, the closed window, and the 24 h peak split by whose speed it is");
    }

    @Test
    @DisplayName("どの数字もツールチップで自分の定義を名乗る")
    void render_everyFigureCarriesItsDefinition() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.recordGeneration(FIRST_WINDOW, measurement(600, 10_000, 1_800));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        String head = html.substring(html.indexOf("<div class=\"figurerows\">"),
                html.indexOf("<div class=\"now\">"));
        assertEquals(countOccurrences(head, "<span title="), countOccurrences(head, "<b>"),
                "every figure in the three rows is wrapped in a span carrying a title");
        assertTrue(head.contains("divided by its 600 seconds"), "the total's divisor is spelled out");
        assertTrue(head.contains("Tokenizers differ between models"), "why chars/s exists");
    }

    @Test
    @DisplayName("3分かかった返答は、終わった1分に積まれず、生成していた3分へ分かれる")
    void bestMinute_spreadsALongReplyOverTheMinutesItGeneratedIn() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // Ends at 11:59:00, having generated for 180 s — so 11:56, 11:57 and 11:58 each get a
        // third of the 1,800 tokens: 600 in a minute, 10.0 tok/s, not 30.0.
        history.recordGeneration(Instant.parse("2026-09-23T11:59:00Z"),
                measurement(1_800, 7_200, 180_000));

        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("<b>10.0</b> <small>busiest 1 min</small>"),
                "a three-minute reply is not a one-minute burst");
        assertFalse(html.contains("<b>30.0</b>"), "charging the whole reply to its last minute");
    }

    @Test
    @DisplayName("1分に収まる返答は、その1分の値になる")
    void bestMinute_aReplyInsideOneMinute_countsThere() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // 1,200 tokens generated in 30 s, wholly inside the minute starting 11:57.
        history.recordGeneration(Instant.parse("2026-09-23T11:57:45Z"),
                measurement(1_200, 4_800, 30_000));

        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("<b>20.0</b> <small>busiest 1 min</small>"),
                "1,200 tokens in the minute that holds them is 20 a second");
    }

    @Test
    @DisplayName("1分の最大は、10分平均の最大以上になる")
    void bestMinute_isAtLeastTheBestWindow() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.recordGeneration(Instant.parse("2026-09-23T11:57:45Z"),
                measurement(1_200, 4_800, 30_000));

        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        // The same 1,200 tokens read as 2.0 a second when divided by the whole ten minutes.
        assertTrue(html.contains("<b>20.0</b> <small>busiest 1 min</small>"));
        assertTrue(html.contains("<b>2.0</b> <small>best 10 min</small>"));
    }

    @Test
    @DisplayName("窓が開く前から生成していた分は、1分の最大に数えない")
    void bestMinute_theSpanBeforeTheWindowOpened_isLeftOut() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // Ends 60 s into the 11:50 window, having generated for 120 s: half of it ran in the
        // window before, which has closed and been written.
        history.recordGeneration(Instant.parse("2026-09-23T11:51:00Z"),
                measurement(1_200, 4_800, 120_000));

        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        // Only the 11:50 minute is inside: 600 tokens there, 10.0 a second.
        assertTrue(html.contains("<b>10.0</b> <small>busiest 1 min</small>"),
                "the half that ran before the window opened is dropped, never carried back");
        assertTrue(html.contains("<b>2.0</b> <small>best 10 min</small>"),
                "the ten-minute total still counts the reply in full");
    }

    @Test
    @DisplayName("24時間の最大は、最も速かった窓と最も速かった返答1件を別々に出す")
    void render_peakRowSeparatesTheWindowPeakFromTheFastestReply() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // 1,000 tokens in 10 s -> 100 tok/s for this one reply, 1.67 tok/s over the window.
        history.recordGeneration(FIRST_WINDOW, measurement(1_000, 4_000, 10_000));

        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("<b>1.7</b> <small>best 10 min</small>"), "the window average");
        assertTrue(html.contains("tok/s <b>100.0</b> <small>alone</small>"), "the one reply");
        // The reply ran inside one minute, but that minute is still 60 seconds long: its 1,000
        // tokens are 16.7 a second of wall clock, against the reply's own 100 while generating.
        assertTrue(html.contains("<b>16.7</b> <small>busiest 1 min</small>"),
                "a minute's rate is over the whole minute, not over the reply inside it");
    }

    @Test
    @DisplayName("2件を足すと、1件あたりの最大は速い方になる")
    void plus_keepsTheFastestSingleReply() {
        GenerationTotals totals = GenerationTotals.NONE
                .plus(measurement(100, 400, 10_000))   // 10 tok/s
                .plus(measurement(100, 400, 1_000));   // 100 tok/s

        assertEquals(100.0, totals.maxTokensPerSecondOneReplyAlone(), 0.001);
        assertEquals(400.0, totals.maxCharactersPerSecondOneReplyAlone(), 0.001);
        assertEquals(2, totals.generations(), "the sums still add up as before");
    }

    /**
     * The point of splitting the per-reply peak: a lone reply is fast because it had the machine,
     * and reporting that as one number hides what a caller gets when the machine is full.
     */
    @Test
    @DisplayName("1本だけで返した速さと、全スロット埋まって返した速さは別の欄に入る")
    void plus_keepsTheSoloAndTheSaturatedReplyApart() {
        GenerationTotals totals = GenerationTotals.NONE
                .plus(reply(1_000, 4_000, 10_000, true, false))    // alone: 100 tok/s
                .plus(reply(1_000, 4_000, 50_000, false, true));   // all slots busy: 20 tok/s

        assertEquals(100.0, totals.maxTokensPerSecondOneReplyAlone(), 0.001);
        assertEquals(20.0, totals.maxTokensPerSecondOneReplyAtFullSlots(), 0.001);
    }

    @Test
    @DisplayName("走行中に空き具合が変わった返答は、どちらの欄にも入らない")
    void plus_aReplyWhoseOccupancyChanged_joinsNeitherPeak() {
        GenerationTotals totals = GenerationTotals.NONE
                .plus(reply(1_000, 4_000, 10_000, false, false));

        assertEquals(0.0, totals.maxTokensPerSecondOneReplyAlone(), 0.001);
        assertEquals(0.0, totals.maxTokensPerSecondOneReplyAtFullSlots(), 0.001);
        assertEquals(1_000, totals.tokens(), "but it still counts in the window's throughput");
    }

    @Test
    @DisplayName("最大値の行は、1本だけのときと全スロット埋まったときを別々に出す")
    void render_peakRowSeparatesTheSoloReplyFromTheSaturatedOne() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.recordGeneration(FIRST_WINDOW, reply(1_000, 4_000, 10_000, true, false));
        history.recordGeneration(FIRST_WINDOW, reply(1_000, 4_000, 50_000, false, true));
        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        // "all slots busy" names a figure in both peak rows; this one belongs to the caller's.
        String caller = html.substring(html.indexOf("24 h peak \u2014 one caller"));
        assertTrue(caller.contains("tok/s <b>100.0</b> <small>alone</small>"));
        assertTrue(caller.contains("<b>20.0</b> <small>all slots busy</small>"));
    }

    /**
     * The node figure has to be conditioned on the deployment actually being full, or it reports
     * whatever the traffic happened to be as though it were the hardware's capacity.
     */
    @Test
    @DisplayName("ノードのスループットは、全スロット埋まっていた分だけから採る")
    void nodePeak_countsOnlyTheMinutesTheObservationFoundFull() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // 11:52 — one slot of two busy, and 1,200 tokens generated inside that minute.
        history.record(Instant.parse("2026-09-23T11:52:30Z"), List.of(), List.of(STATUS));
        history.recordGeneration(Instant.parse("2026-09-23T11:52:45Z"),
                measurement(1_200, 4_800, 30_000));
        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        String node = html.substring(html.indexOf("24 h peak \u2014 node"),
                html.indexOf("24 h peak \u2014 one caller"));
        assertTrue(node.contains("tok/s <b>&mdash;</b> <small>all slots busy</small>"),
                "the queue was never observed full, so it has no throughput figure as a machine");
        assertTrue(node.contains("<b>20.0</b> <small>busiest 1 min</small>"),
                "the busiest minute is still reported, at whatever occupancy it ran");
    }

    @Test
    @DisplayName("全スロット埋まっていた分の生成量が、ノードのスループットになる")
    void nodePeak_aFullMinute_becomesTheThroughputFigure() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // Both slots busy at 11:52, and 1,200 tokens generated inside that minute.
        QueueStatus full = new QueueStatus(QUEUE, new QueueSnapshot(
                List.of("192.168.5.16:8000#0", "192.168.5.16:8000#1"), List.of(), 0, 0, 0));
        history.record(Instant.parse("2026-09-23T11:52:30Z"), List.of(), List.of(full));
        history.recordGeneration(Instant.parse("2026-09-23T11:52:45Z"),
                measurement(1_200, 4_800, 30_000));
        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("tok/s <b>20.0</b> <small>all slots busy</small>"),
                "1,200 tokens in a minute the observation found full");
    }

    @Test
    @DisplayName("直前の10分の行は、その値が何本詰まった状態で測られたかを出す")
    void render_theTenMinuteRowSaysHowBusyTheQueueWas() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.record(FIRST_WINDOW, List.of(), List.of(STATUS));   // 1 active, 1 idle
        history.recordGeneration(FIRST_WINDOW, measurement(600, 2_400, 10_000));
        closeThatWindow(history);

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("at <b>1.0</b> <small>slots busy</small>"),
                "the occupancy the per-reply mean beside it was measured at");
    }

    private static GenerationMeasurement reply(long tokens, long characters, long decodeMs,
                                               boolean alone, boolean atFullSlots) {
        return new GenerationMeasurement(QUEUE, "192.168.5.16:8000", 0, 0, decodeMs, tokens,
                characters, alone, atFullSlots);
    }

    @Test
    @DisplayName("保持期間を過ぎた窓が落ちると、その最大値も一緒に消える")
    void peaks_coverOnlyTheWindowsStillRetained() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.recordGeneration(FIRST_WINDOW, measurement(1_000, 4_000, 10_000));

        Instant muchLater = FIRST_WINDOW.plus(java.time.Duration.ofHours(25));
        history.record(muchLater, List.of(), List.of(STATUS));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), muchLater);

        assertFalse(html.contains("24 h peak"), "the only window that ever had a peak was pruned");
    }

    @Test
    @DisplayName("測れたが丸めで消える速さは、0.0 ではなく <0.1 と出す")
    void render_aRateTooSmallToRound_saysSoRatherThanReadingZero() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        // 12 tokens in a closed window -> 0.02 tok/s, which %.1f writes as 0.0.
        history.recordGeneration(FIRST_WINDOW, measurement(12, 40, 2_000));

        String html = StatusPageRenderer.render(List.of(STATUS), Map.of(),
                historyFor(history, STATUS), INSIDE_SECOND_WINDOW);

        assertTrue(html.contains("tok/s <b>&lt;0.1</b> <small>total</small>"),
                "a handful of tokens is not none");
        // Only the rates: a count of busy slots may legitimately read 0.0, a speed may not.
        assertFalse(html.contains("tok/s <b>0.0</b>"), "no rate may read as a measured zero");
        assertFalse(html.contains("<b>0.0</b> <small>per reply</small>"));
        assertFalse(html.contains("<b>0.0</b> <small>chars/s per reply</small>"));
    }

    /**
     * What the minute's observation does in production: rolls the clock past the window the
     * replies were recorded in, which is when its busiest minute can be known.
     */
    private static void closeThatWindow(StatusHistoryStore history) {
        history.record(INSIDE_SECOND_WINDOW, List.of(), List.of(STATUS));
    }

    /** A reply that had the queue to itself, which is the case most of these tests describe. */
    private static GenerationMeasurement measurement(long tokens, long characters, long decodeMs) {
        return new GenerationMeasurement(QUEUE, "192.168.5.16:8000", 0, 0, decodeMs, tokens,
                characters, true, false);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static Map<String, QueueHistorySnapshot> historyFor(StatusHistoryStore history, QueueStatus... statuses) {
        Map<String, QueueHistorySnapshot> result = new LinkedHashMap<>();
        for (QueueStatus status : statuses) {
            result.put(status.queueName(),
                    history.snapshotFor(status.queueName(), QueueReport.registeredAddressesOf(status)));
        }
        return result;
    }
}
