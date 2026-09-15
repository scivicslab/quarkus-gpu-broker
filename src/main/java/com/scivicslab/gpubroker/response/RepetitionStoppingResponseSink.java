package com.scivicslab.gpubroker.response;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Logger;

import com.scivicslab.gpubroker.model.ResponseSink;

/**
 * A {@link ResponseSink} that forwards everything and, when the text coming back has turned into
 * one block repeated without end, asks for the upstream response to be closed
 * ({@code RunawayGenerationLimits_260915_oo01}).
 *
 * <p>A cap on tokens bounds how long a runaway lasts; it does not notice one. With a context length
 * of 131,072 a capped reply can still be tens of thousands of tokens of the same sentence, which is
 * a slot held and a GPU spent. This watches instead: once the window is full of text whose 64-byte
 * pieces are nearly all the same few pieces, the reply has stopped saying anything new.</p>
 *
 * <p>What is watched is the generated text -- the {@code content} of each streamed chunk -- and not
 * the bytes on the wire. Server-sent events repeat their own framing on every token, so a window
 * over the raw stream is periodic in every reply, runaway or not.</p>
 *
 * <p>Bytes rather than characters throughout: the markers are ASCII, so a multi-byte character
 * split across two chunks never has to be reassembled, and a repeated block is as periodic in
 * UTF-8 as it is in text.</p>
 */
public final class RepetitionStoppingResponseSink implements ResponseSink {

    private static final Logger LOG = Logger.getLogger(RepetitionStoppingResponseSink.class.getName());

    /** How much recent generated text is judged at once. */
    static final int WINDOW = 8192;
    /** The size of the pieces the window is cut into. */
    private static final int PIECE = 64;
    /** How much new text to take before judging again. */
    private static final int JUDGE_EVERY = 1024;
    /**
     * The share of distinct pieces below which the window counts as repeating. Prose of this length
     * is made almost entirely of pieces it never uses again; a block repeated k times leaves only
     * one block's worth of distinct pieces however long the window is.
     */
    private static final double DISTINCT_SHARE = 0.25;

    /** Where the generated text sits inside one streamed chunk. Matches {@code reasoning_content} too. */
    private static final byte[] MARKER = "content\":\"".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] CUT_OFF = ("data: {\"choices\":[{\"index\":0,\"delta\":{},"
            + "\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);

    /** A response that is one JSON object rather than events never reaches a newline; do not hoard it. */
    private static final int PENDING_LIMIT = 65536;

    private final ResponseSink delegate;
    private final String queueName;

    private final byte[] window = new byte[WINDOW];
    private int next;
    private long taken;
    private int sinceJudged;

    private byte[] pending = new byte[0];
    private boolean events;
    private volatile boolean stop;

    /**
     * @param delegate  where everything is forwarded, unchanged and in order
     * @param queueName named in the warning, so a run that was cut can be found afterwards
     */
    public RepetitionStoppingResponseSink(ResponseSink delegate, String queueName) {
        this.delegate = delegate;
        this.queueName = queueName;
    }

    @Override
    public void dispatched() {
        delegate.dispatched();
    }

    @Override
    public void start(String contentType) {
        events = contentType != null && contentType.startsWith("text/event-stream");
        delegate.start(contentType);
    }

    @Override
    public void emit(byte[] chunk) {
        delegate.emit(chunk);
        if (stop) {
            return;
        }
        take(chunk);
        if (sinceJudged >= JUDGE_EVERY && taken >= WINDOW && repeating()) {
            stop = true;
            LOG.warning("Cut off a repeating reply on " + queueName + " after " + taken
                    + " bytes of generated text");
            if (events) {
                delegate.emit(CUT_OFF);
            }
        }
    }

    @Override
    public boolean stopRequested() {
        return stop;
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    @Override
    public void fail(Throwable cause) {
        delegate.fail(cause);
    }

    /** Keeps whole lines only, so a marker split across two chunks is read once it is complete. */
    private void take(byte[] chunk) {
        byte[] all = concat(pending, chunk);
        int lastNewline = lastIndexOf(all, (byte) '\n');
        if (lastNewline < 0) {
            pending = all.length > PENDING_LIMIT ? new byte[0] : all;
            return;
        }
        pending = Arrays.copyOfRange(all, lastNewline + 1, all.length);
        takeGeneratedText(all, lastNewline + 1);
    }

    private void takeGeneratedText(byte[] lines, int end) {
        int at = 0;
        while (at < end) {
            int marker = indexOf(lines, MARKER, at, end);
            if (marker < 0) {
                return;
            }
            int from = marker + MARKER.length;
            int to = from;
            while (to < end && lines[to] != '"') {
                to += lines[to] == '\\' ? 2 : 1;
            }
            append(lines, from, Math.min(to, end));
            at = Math.min(to, end) + 1;
        }
    }

    private void append(byte[] source, int from, int to) {
        for (int i = from; i < to; i++) {
            window[next] = source[i];
            next = next + 1 == WINDOW ? 0 : next + 1;
        }
        taken += to - from;
        sinceJudged += to - from;
    }

    /**
     * True when the window's pieces are nearly all repeats of one another. Judged over the pieces
     * rather than by looking for a period, so a block of any length is caught by the same rule.
     */
    private boolean repeating() {
        sinceJudged = 0;
        byte[] ordered = ordered();
        int pieces = ordered.length - PIECE + 1;
        if (pieces <= 0) {
            return false;
        }
        long[] hashes = new long[pieces];
        long hash = 0;
        long highest = 1;
        for (int i = 0; i < PIECE; i++) {
            hash = hash * 131 + ordered[i];
            if (i > 0) {
                highest *= 131;
            }
        }
        hashes[0] = hash;
        for (int i = 1; i < pieces; i++) {
            hash = (hash - ordered[i - 1] * highest) * 131 + ordered[i + PIECE - 1];
            hashes[i] = hash;
        }
        Arrays.sort(hashes);
        int distinct = 1;
        for (int i = 1; i < pieces; i++) {
            if (hashes[i] != hashes[i - 1]) {
                distinct++;
            }
        }
        return (double) distinct / pieces <= DISTINCT_SHARE;
    }

    private byte[] ordered() {
        if (taken < WINDOW) {
            return Arrays.copyOf(window, next);
        }
        byte[] out = new byte[WINDOW];
        System.arraycopy(window, next, out, 0, WINDOW - next);
        System.arraycopy(window, 0, out, WINDOW - next, next);
        return out;
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
