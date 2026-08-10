package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

@DisplayName("StatusPageRenderer — HTML for GET /status")
class StatusPageRendererTest {

    @Test
    void noQueues_rendersAPlaceholderMessage() {
        String html = StatusPageRenderer.render(List.of());

        assertTrue(html.contains("No queues discovered yet."));
    }

    @Test
    void oneQueue_rendersItsNameAndCounts() {
        QueueStatus status = new QueueStatus("vllm-gemma4", new QueueSnapshot(1, 2, 3));

        String html = StatusPageRenderer.render(List.of(status));

        assertTrue(html.contains("vllm-gemma4"));
        assertTrue(html.contains("active: 1"));
        assertTrue(html.contains("idle: 2"));
        assertTrue(html.contains("pending: 3"));
    }

    @Test
    void allZeroCounts_rendersNoBarSegments() {
        QueueStatus status = new QueueStatus("idle-queue", new QueueSnapshot(0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status));

        assertFalse(html.contains("class=\"active\""));
        assertFalse(html.contains("class=\"idle\""));
        assertFalse(html.contains("class=\"pending\""));
    }

    @Test
    void queueNameIsHtmlEscaped() {
        QueueStatus status = new QueueStatus("a<b>&c", new QueueSnapshot(0, 0, 0));

        String html = StatusPageRenderer.render(List.of(status));

        assertTrue(html.contains("a&lt;b&gt;&amp;c"));
        assertFalse(html.contains("a<b>&c"));
    }

    @Test
    void refreshesEveryTenSeconds() {
        String html = StatusPageRenderer.render(List.of());

        assertTrue(html.contains("<meta http-equiv=\"refresh\" content=\"10\">"));
    }
}
