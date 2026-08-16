package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

@DisplayName("StatusPageRenderer — HTML for the status page")
class StatusPageRendererTest {

    @Test
    void noQueues_rendersAPlaceholderMessage() {
        String html = StatusPageRenderer.render(List.of(), Map.of());

        assertTrue(html.contains("No queues discovered yet."));
    }

    @Test
    void oneQueue_rendersItsNameAndCounts() {
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of("192.168.5.16:8000"), List.of("192.168.5.17:8000", "192.168.5.14:8000"), 3));

        String html = StatusPageRenderer.render(List.of(status), Map.of());

        assertTrue(html.contains("vllm-gemma4"));
        assertTrue(html.contains("active: 1"));
        assertTrue(html.contains("idle: 2"));
        assertTrue(html.contains("pending: 3"));
    }

    @Test
    void oneQueue_listsTheActualEndpointAddresses() {
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of("192.168.5.16:8000"), List.of("192.168.5.17:8000"), 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of());

        assertTrue(html.contains("192.168.5.16:8000 (active)"));
        assertTrue(html.contains("192.168.5.17:8000 (idle)"));
    }

    @Test
    void allEmpty_rendersNoBarSegmentsAndNoEndpointList() {
        QueueStatus status = new QueueStatus("idle-queue", new QueueSnapshot(List.of(), List.of(), 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of());

        assertFalse(html.contains("class=\"active\""));
        assertFalse(html.contains("class=\"idle\""));
        assertFalse(html.contains("class=\"pending\""));
        assertFalse(html.contains("endpoints:"));
    }

    @Test
    void queueNameAndEndpointIdAreHtmlEscaped() {
        QueueStatus status = new QueueStatus("a<b>&c", new QueueSnapshot(List.of("x<y"), List.of(), 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of());

        assertTrue(html.contains("a&lt;b&gt;&amp;c"));
        assertFalse(html.contains("a<b>&c"));
        assertTrue(html.contains("x&lt;y"));
        assertFalse(html.contains("x<y"));
    }

    @Test
    void refreshesEveryTenSeconds() {
        String html = StatusPageRenderer.render(List.of(), Map.of());

        assertTrue(html.contains("<meta http-equiv=\"refresh\" content=\"10\">"));
    }

    @Test
    void endpointWithDeclaredCapability_showsItLabeledAsDeclared() {
        // endpointId is a Worker name ("host:port#slot"); the capability map is keyed by the bare address.
        QueueStatus status = new QueueStatus("vllm-gemma4",
                new QueueSnapshot(List.of("192.168.5.14:8000#0"), List.of(), 0));
        Map<String, BrokerConfig.EndpointCapability> capabilities =
                Map.of("192.168.5.14:8000", new StubEndpointCapability(32768, true, null));

        String html = StatusPageRenderer.render(List.of(status), capabilities);

        assertTrue(html.contains("192.168.5.14:8000#0 (active)"));
        assertTrue(html.contains("context 32768"));
        assertTrue(html.contains("thinking true"));
        assertTrue(html.contains("(declared)"));
    }

    @Test
    void endpointWithoutDeclaredCapability_showsNoDeclaredLabel() {
        QueueStatus status = new QueueStatus("yomitoku-ocr",
                new QueueSnapshot(List.of("192.168.5.16:8013#0"), List.of(), 0));

        String html = StatusPageRenderer.render(List.of(status), Map.of());

        assertFalse(html.contains("(declared)"));
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
