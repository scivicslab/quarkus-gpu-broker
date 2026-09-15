package com.scivicslab.gpubroker.response;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pulls the generated text out of a server-sent-event stream as it arrives, and counts the events
 * that carried any ({@code GenerationRateOnTheStatusPage_260915_oo01}).
 *
 * <p>Two sinks need this and neither needs the other: one watches the text for a model that has
 * started repeating itself, the other counts how fast it is arriving.</p>
 *
 * <p>Bytes rather than characters: the markers are ASCII, so a multi-byte character split across
 * two chunks never has to be reassembled.</p>
 */
public final class SseContentScanner {

    /** Where the generated text sits inside one streamed chunk. Matches {@code reasoning_content} too. */
    private static final byte[] MARKER = "content\":\"".getBytes(StandardCharsets.US_ASCII);

    /** A response that is one JSON object rather than events never reaches a newline; do not hoard it. */
    private static final int PENDING_LIMIT = 65536;

    /** Receives one run of generated text, as a range inside the caller's own array. */
    public interface Found {
        void text(byte[] source, int from, int to);
    }

    private byte[] pending = new byte[0];
    private long events;
    private long characters;

    /**
     * Takes one chunk off the wire. Whole lines only are read: a marker split across two chunks is
     * read once the rest of it has arrived.
     *
     * @param chunk what came off the wire
     * @param found told about every run of generated text in it, in order
     */
    public void feed(byte[] chunk, Found found) {
        byte[] all = concat(pending, chunk);
        int lastNewline = lastIndexOf(all, (byte) '\n');
        if (lastNewline < 0) {
            pending = all.length > PENDING_LIMIT ? new byte[0] : all;
            return;
        }
        pending = Arrays.copyOfRange(all, lastNewline + 1, all.length);
        int end = lastNewline + 1;
        int at = 0;
        while (at < end) {
            int marker = indexOf(all, MARKER, at, end);
            if (marker < 0) {
                return;
            }
            int from = marker + MARKER.length;
            int to = from;
            while (to < end && all[to] != '"') {
                to += all[to] == '\\' ? 2 : 1;
            }
            to = Math.min(to, end);
            if (to > from) {
                events++;
                characters += charactersIn(all, from, to);
                found.text(all, from, to);
            }
            at = to + 1;
        }
    }

    /**
     * How many events carried generated text.
     *
     * <p>Read as a token count, which is what it is while a server sends one token per event --
     * vLLM does. A server that batches several tokens into one event makes this an undercount, so
     * it is a rate to watch over time for one model, not a figure to compare between servers.</p>
     */
    public long events() {
        return events;
    }

    /**
     * How many characters of generated text have come through.
     *
     * <p>Unlike {@link #events}, comparable between models: one sentence is the same number of
     * characters whatever tokenizer produced it.</p>
     */
    public long characters() {
        return characters;
    }

    /**
     * How many characters one run of generated text is.
     *
     * <p>The unit that survives a change of model: a tokenizer decides how many tokens a sentence
     * is, but not how many characters ({@code GenerationRateOnTheStatusPage_260915_oo01}).</p>
     *
     * <p>Counted off the UTF-8 bytes without decoding them: every character is one leading byte
     * followed by its continuation bytes. A JSON escape is one character too -- {@code \n} is two
     * bytes and {@code \u3042} is six.</p>
     */
    private static long charactersIn(byte[] text, int from, int to) {
        long count = 0;
        int i = from;
        while (i < to) {
            if (text[i] == '\\') {
                i += i + 1 < to && text[i + 1] == 'u' ? 6 : 2;
                count++;
                continue;
            }
            if ((text[i] & 0xC0) != 0x80) {
                count++;
            }
            i++;
        }
        return count;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        if (head.length == 0) {
            return tail;
        }
        byte[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private static int lastIndexOf(byte[] haystack, byte needle) {
        for (int i = haystack.length - 1; i >= 0; i--) {
            if (haystack[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int end) {
        outer:
        for (int i = from; i <= end - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
