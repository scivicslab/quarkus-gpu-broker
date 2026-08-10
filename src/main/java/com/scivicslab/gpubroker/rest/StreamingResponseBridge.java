package com.scivicslab.gpubroker.rest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.scivicslab.gpubroker.model.ResponseSink;

/**
 * Hands relayed chunks from the {@code NodeActor} thread to the request thread
 * without either touching the other's stream.
 *
 * <p>The relay (running on a {@code NodeActor}'s virtual thread) only enqueues
 * chunks via {@link #write}; the request thread alone drains the queue in
 * {@link #writeTo} and writes them to the HTTP output stream as they arrive.
 * This is what makes live passthrough safe: the response output stream is only
 * ever written by its owning request thread, while chunks still flow in from
 * another thread. {@link #finish} enqueues an end marker so {@code writeTo}
 * returns when the relay has completed.
 */
public final class StreamingResponseBridge implements ResponseSink {

    private static final Object END = new Object();

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

    @Override
    public void write(String chunk) {
        queue.add(chunk);
    }

    /** Signal end of stream so a parked {@link #writeTo} returns. */
    public void finish() {
        queue.add(END);
    }

    /** Drain chunks to the client output stream until {@link #finish} is seen. */
    public void writeTo(OutputStream out) throws IOException {
        try {
            Object item;
            while ((item = queue.take()) != END) {
                out.write(((String) item).getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();                       // push each chunk to the client immediately
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
