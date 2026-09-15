package com.scivicslab.gpubroker.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.scivicslab.gpubroker.history.GenerationTotals;
import com.scivicslab.gpubroker.model.GenerationMeasurement;
import com.scivicslab.gpubroker.model.ResponseSink;

import org.junit.jupiter.api.Test;

class GenerationMeasuringResponseSinkTest {

    private static final class Kept implements ResponseSink {
        final List<String> chunks = new ArrayList<>();
        @Override public void start(String contentType) { }
        @Override public void emit(byte[] chunk) { chunks.add(new String(chunk, StandardCharsets.UTF_8)); }
        @Override public void complete() { }
        @Override public void fail(Throwable cause) { }
    }

    private static byte[] event(String content) {
        return ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + content + "\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void oneReply_isCountedAndAttributedToTheServerThatRanIt() {
        Kept kept = new Kept();
        List<GenerationMeasurement> reported = new ArrayList<>();
        GenerationMeasuringResponseSink sink =
                new GenerationMeasuringResponseSink(kept, "vllm-test", reported::add);

        sink.dispatched();
        sink.servedBy("192.168.5.16:8000");
        sink.start("text/event-stream");
        sink.emit(event("one "));
        sink.emit(event("two "));
        sink.emit(event("three"));
        sink.complete();

        assertEquals(1, reported.size());
        GenerationMeasurement one = reported.get(0);
        assertEquals("vllm-test", one.queueName());
        assertEquals("192.168.5.16:8000", one.address());
        assertEquals(3, one.tokens(), "one event carrying text counts as one token");
        assertEquals(3, kept.chunks.size(), "everything is forwarded unchanged");
    }

    @Test
    void framingWithoutGeneratedText_isNotCounted() {
        List<GenerationMeasurement> reported = new ArrayList<>();
        GenerationMeasuringResponseSink sink =
                new GenerationMeasuringResponseSink(new Kept(), "vllm-test", reported::add);

        sink.dispatched();
        sink.start("text/event-stream");
        // The last chunk of a reply carries an empty delta and the reason it stopped.
        sink.emit("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                .getBytes(StandardCharsets.UTF_8));
        sink.emit("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        sink.complete();

        assertEquals(0, reported.get(0).tokens());
    }

    @Test
    void aReplyThatFailedBeforeItStarted_stillReportsWhatItWaited() {
        List<GenerationMeasurement> reported = new ArrayList<>();
        GenerationMeasuringResponseSink sink =
                new GenerationMeasuringResponseSink(new Kept(), "vllm-test", reported::add);

        sink.dispatched();
        sink.fail(new IllegalStateException("every endpoint refused"));

        assertEquals(1, reported.size());
        assertNull(reported.get(0).address(), "it never reached one");
        assertEquals(0, reported.get(0).tokens());
    }

    @Test
    void endingTwice_reportsOnce() {
        List<GenerationMeasurement> reported = new ArrayList<>();
        GenerationMeasuringResponseSink sink =
                new GenerationMeasuringResponseSink(new Kept(), "vllm-test", reported::add);

        sink.dispatched();
        sink.complete();
        sink.fail(new IllegalStateException("late"));

        assertEquals(1, reported.size());
    }

    @Test
    void theTwoRates_areDifferentDivisionsOfTheSameSums() {
        // Four replies of 100 tokens each, every one of them taking 10 seconds of generation.
        GenerationTotals totals = GenerationTotals.NONE;
        for (int i = 0; i < 4; i++) {
            totals = totals.plus(new GenerationMeasurement("vllm-test", "a:1", 2000, 300, 10_000, 100));
        }

        // One reply at a time reads as 10 tok/s …
        assertEquals(10.0, totals.tokensPerSecondPerReply(), 1e-9);
        // … while the machine produced 400 tokens over the ten-minute window.
        assertEquals(400 / 600.0, totals.tokensPerSecondOver(Duration.ofMinutes(10)), 1e-9);
        assertEquals(2000, totals.meanQueuedMs());
        assertEquals(300, totals.meanFirstMs());
    }

    @Test
    void sums_addUpAcrossBuckets() {
        GenerationTotals one = GenerationTotals.NONE
                .plus(new GenerationMeasurement("q", "a:1", 1, 2, 3, 4));
        GenerationTotals two = GenerationTotals.NONE
                .plus(new GenerationMeasurement("q", "a:1", 10, 20, 30, 40));
        GenerationTotals both = one.plus(two);

        assertEquals(2, both.generations());
        assertEquals(44, both.tokens());
        assertEquals(33, both.decodeMs());
        assertNotNull(GenerationTotals.NONE);
        assertTrue(both.tokensPerSecondPerReply() > 0);
    }
}
