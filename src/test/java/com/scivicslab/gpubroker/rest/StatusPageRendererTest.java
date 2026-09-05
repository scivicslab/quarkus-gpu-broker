package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.history.StatusHistoryStore;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

@DisplayName("StatusPageRenderer — HTML for the status page")
class StatusPageRendererTest {

    @Test
    void noQueues_rendersAPlaceholderMessage() {
        String html = StatusPageRenderer.render(List.of(), Map.of(), emptyHistory());

        assertTrue(html.contains("No queues discovered yet."));
    }

    @Test
    void oneQueue_rendersItsNameAndCounts() {
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of("192.168.5.16:8000"), List.of("192.168.5.17:8000", "192.168.5.14:8000"), 3, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertTrue(html.contains("vllm-gemma4"));
        assertTrue(html.contains("active <b>1</b>"));
        assertTrue(html.contains("idle <b>2</b>"));
        assertTrue(html.contains("pending <b>3</b>"));
    }

    /**
     * active and idle count workers, pending counts jobs. The page spells the unit out next to
     * each number so a reader does not have to already know which of the three is which.
     */
    @Test
    void eachCountCarriesItsUnit() {
        QueueStatus status = new QueueStatus("vllm-gemma4", new QueueSnapshot(
                List.of("192.168.5.16:8000#0"), List.of("192.168.5.16:8000#1"), 7, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertTrue(html.contains("active <b>1</b> <small>slots</small>"));
        assertTrue(html.contains("pending <b>7</b> <small>jobs</small>"));
        assertTrue(html.contains("idle <b>1</b> <small>slots</small>"));
        assertTrue(html.contains("<small>jobs/h</small>"));
    }

    @Test
    void oneQueue_listsTheActualEndpointAddresses() {
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of("192.168.5.16:8000"), List.of("192.168.5.17:8000"), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertTrue(html.contains("192.168.5.16:8000"));
        assertTrue(html.contains("192.168.5.17:8000"));
    }

    /**
     * One row per physical address, not per worker: a single address runs maxConcurrency workers
     * (192.168.5.16:8000#0, #1, ...) which would otherwise be one liveness row each.
     */
    @Test
    void severalWorkersOfOneAddress_collapseToOneRow() {
        QueueStatus status = new QueueStatus("vllm-gemma4", new QueueSnapshot(
                List.of("192.168.5.16:8000#0"), List.of("192.168.5.16:8000#1", "192.168.5.16:8000#2"), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertEquals(1, countOccurrences(html, "<span class=\"addr\">192.168.5.16:8000"));
        assertFalse(html.contains("192.168.5.16:8000#0"));
    }

    /** Before the first probe lands there is no history, and the addresses must still be listed. */
    @Test
    void withoutAnyHistory_stillListsRegisteredAddresses() {
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of(), List.of("192.168.5.17:8000#0"), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertTrue(html.contains("192.168.5.17:8000"));
        assertFalse(html.contains("No probe result recorded yet."));
    }

    @Test
    void allEmpty_rendersNoBarSegmentsAndNoEndpointList() {
        QueueStatus status = new QueueStatus("idle-queue", new QueueSnapshot(List.of(), List.of(), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertFalse(html.contains("class=\"active\""));
        assertFalse(html.contains("class=\"idle\""));
        assertFalse(html.contains("class=\"pending\""));
        assertFalse(html.contains("endpoints:"));
    }

    @Test
    void queueNameAndEndpointIdAreHtmlEscaped() {
        QueueStatus status = new QueueStatus("a<b>&c", new QueueSnapshot(List.of("x<y"), List.of(), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertTrue(html.contains("a&lt;b&gt;&amp;c"));
        assertFalse(html.contains("a<b>&c"));
        assertTrue(html.contains("x&lt;y"));
        assertFalse(html.contains("x<y"));
    }

    @Test
    void refreshesEveryTenSeconds() {
        String html = StatusPageRenderer.render(List.of(), Map.of(), emptyHistory());

        assertTrue(html.contains("<meta http-equiv=\"refresh\" content=\"10\">"));
    }

    @Test
    void endpointWithDeclaredCapability_showsItLabeledAsDeclared() {
        // endpointId is a Worker name ("host:port#slot"); the capability map is keyed by the bare address.
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of("192.168.5.14:8000#0"), List.of(), 0, 0, 0));
        Map<String, BrokerConfig.EndpointCapability> capabilities =
                Map.of("192.168.5.14:8000", new StubEndpointCapability(32768, true, null));

        String html = StatusPageRenderer.render(List.of(status), capabilities, emptyHistory());

        assertTrue(html.contains("192.168.5.14:8000"));
        assertTrue(html.contains("context 32768"));
        assertTrue(html.contains("thinking true"));
        assertTrue(html.contains("(declared)"));
    }

    @Test
    void endpointWithoutDeclaredCapability_showsNoDeclaredLabel() {
        QueueStatus status = new QueueStatus("yomitoku-ocr",
                new QueueSnapshot(List.of("192.168.5.16:8013#0"), List.of(), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertFalse(html.contains("(declared)"));
    }


    /** The current bar's full width is the slot count, so a full bar means every slot is busy. */
    @Test
    void currentBarIsScaledToTheSlotCount() {
        QueueStatus status = new QueueStatus("q", new QueueSnapshot(
                List.of("a:1#0", "a:1#1"), List.of("a:1#2", "a:1#3"), 0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of(), emptyHistory());

        assertTrue(html.contains("4 slots"), "the bar's ceiling is named as the slot count");
    }

    /** The axis must read in round numbers, not in whatever the peak happened to be. */
    @Test
    void axisCeilingIsARoundNumber() {
        assertEquals(1.0, StatusPageRenderer.niceCeiling(0.9));
        assertEquals(1.0, StatusPageRenderer.niceCeiling(1.0));
        assertEquals(2.0, StatusPageRenderer.niceCeiling(1.1));
        assertEquals(5.0, StatusPageRenderer.niceCeiling(4.2));
        assertEquals(10.0, StatusPageRenderer.niceCeiling(7.0));
        assertEquals(20.0, StatusPageRenderer.niceCeiling(11.0));
        assertEquals(100.0, StatusPageRenderer.niceCeiling(51.0));
    }

    /** Waiting jobs and completed jobs are both counted in jobs, so they share one axis. */
    @Test
    void queueAndThroughputSharesOneAxisAndUtilizationIsAPercentage() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        Instant t = Instant.parse("2026-09-05T12:00:00Z");
        history.record(t, List.of(), List.of(new QueueStatus("q",
                new QueueSnapshot(List.of("a:1#0"), List.of("a:1#1"), 6, 0, 0))));
        QueueStatus now = new QueueStatus("q", new QueueSnapshot(List.of("a:1#0"), List.of("a:1#1"), 6, 0, 0));

        String html = StatusPageRenderer.render(List.of(now), Map.of(), history);

        assertTrue(html.contains("waiting and done"));
        assertTrue(html.contains("jobs &mdash; area: waiting, line: done per 10 min"));
        assertTrue(html.contains("slot utilization"));
        assertTrue(html.contains("% of 2 slots"));
        assertTrue(html.contains("100%"), "the utilization axis is labelled 0/50/100 percent");
        assertTrue(html.contains("50%"));
    }

    /** The header has to say what changes when, since three different intervals are in play. */
    @Test
    void headerNamesEachUpdateInterval() {
        String html = StatusPageRenderer.render(List.of(), Map.of(), emptyHistory());

        assertTrue(html.contains("reload every 10s"));
        assertTrue(html.contains("probed every minute"));
        assertTrue(html.contains("one step every 10 min"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static StatusHistoryStore emptyHistory() {
        return new StatusHistoryStore(null);
    }

    private record StubEndpointCapability(Integer declaredMaxContextLength, Boolean declaredThinkingModeSupported,
                                           Boolean declaredToolCallingSupported) implements BrokerConfig.EndpointCapability {
        @Override
        public OptionalInt maxConcurrency() {
            return OptionalInt.empty();
        }

        @Override
        public OptionalInt maxContextLength() {
            return declaredMaxContextLength == null ? OptionalInt.empty() : OptionalInt.of(declaredMaxContextLength);
        }

        @Override
        public Optional<Boolean> thinkingModeSupported() {
            return Optional.ofNullable(declaredThinkingModeSupported);
        }

        @Override
        public Optional<Boolean> toolCallingSupported() {
            return Optional.ofNullable(declaredToolCallingSupported);
        }
    }
}
