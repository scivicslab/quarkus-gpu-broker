package com.scivicslab.gpubroker.response;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.scivicslab.gpubroker.model.ResponseSink;

import org.junit.jupiter.api.Test;

class RepetitionStoppingResponseSinkTest {

    /** Keeps what it was given, so a test can check that everything was forwarded unchanged. */
    private static final class Kept implements ResponseSink {
        final List<String> chunks = new ArrayList<>();
        boolean completed;

        @Override public void start(String contentType) { }
        @Override public void emit(byte[] chunk) { chunks.add(new String(chunk, StandardCharsets.UTF_8)); }
        @Override public void complete() { completed = true; }
        @Override public void fail(Throwable cause) { }

        String all() { return String.join("", chunks); }
    }

    private static byte[] event(String content) {
        String escaped = content.replace("\"", "\\\"");
        return ("data: {\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"choices\":"
                + "[{\"index\":0,\"delta\":{\"content\":\"" + escaped + "\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static RepetitionStoppingResponseSink watching(Kept kept) {
        RepetitionStoppingResponseSink sink = new RepetitionStoppingResponseSink(kept, "vllm-test");
        sink.start("text/event-stream");
        return sink;
    }

    @Test
    void emit_oneSentenceRepeatedWithoutEnd_asksToStop() {
        Kept kept = new Kept();
        RepetitionStoppingResponseSink sink = watching(kept);
        // The shape an actual runaway had: the model announcing the same no-op change forever.
        String unit = "I will change \"plugin-harness pluginprocess\" to \"plugin-harness pluginprocess\". ";
        for (int i = 0; i < 400 && !sink.stopRequested(); i++) {
            sink.emit(event(unit));
        }
        assertTrue(sink.stopRequested(), "a reply that says the same thing forever must be cut off");
        assertTrue(kept.all().endsWith("data: [DONE]\n\n"), "the client must be told the reply ended");
        assertTrue(kept.all().contains("\"finish_reason\":\"length\""));
    }

    @Test
    void emit_aLongReplyThatKeepsSayingNewThings_isLeftAlone() {
        Kept kept = new Kept();
        RepetitionStoppingResponseSink sink = watching(kept);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            text.setLength(0);
            text.append("Section ").append(i).append(": the endpoint at 192.168.5.")
                .append(i % 200).append(" answered in ").append(i * 7 % 991).append(" ms. ");
            sink.emit(event(text.toString()));
        }
        assertFalse(sink.stopRequested(), "prose that keeps moving must not be cut off");
        assertFalse(kept.all().contains("finish_reason"));
    }

    @Test
    void emit_repeatedFramingWithShortVariedContent_isLeftAlone() {
        // Every event carries the same id, object and delta scaffolding. Judging the bytes on the
        // wire instead of the generated text would call every reply a runaway.
        Kept kept = new Kept();
        RepetitionStoppingResponseSink sink = watching(kept);
        for (int i = 0; i < 4000; i++) {
            sink.emit(event(Integer.toHexString(i * 2654435761L != 0 ? i * 31 + 7 : i) + " "));
        }
        assertFalse(sink.stopRequested());
    }

    @Test
    void emit_everythingIsForwardedUntilTheCut() {
        Kept kept = new Kept();
        RepetitionStoppingResponseSink sink = watching(kept);
        sink.emit(event("hello "));
        sink.emit(event("world"));
        sink.complete();
        assertTrue(kept.all().contains("hello "));
        assertTrue(kept.all().contains("world"));
        assertTrue(kept.completed);
        assertFalse(sink.stopRequested());
    }
}
