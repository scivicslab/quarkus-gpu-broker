package com.scivicslab.gpubroker.response;

import java.util.concurrent.atomic.AtomicBoolean;

import com.scivicslab.gpubroker.model.ResponseSink;

import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.buffer.Buffer;

/**
 * The synchronous-streaming {@code ResponseSink}: relays chunks straight
 * into the original HTTP connection as they arrive.
 *
 * <p>{@link #data} is a {@code UnicastProcessor}, not a {@code
 * Multi.createFrom().emitter(...)} — the latter only hands out its emitter
 * once actually subscribed to, but {@code ProxyResource} has to build the
 * {@code Job} and dispatch it (which may start calling {@link #emit}) before
 * {@code Content-Type} is known and therefore before Quarkus has subscribed
 * to anything. {@code UnicastProcessor} accepts {@code onNext} immediately
 * and queues whatever arrives before its one subscriber attaches.
 *
 * <p>{@link #started} is a second, separate terminal signal: the original
 * HTTP response ({@code RestMulti.fromUniResponse}) will not even begin —
 * no status line, no headers — until it resolves. {@link #start} is what
 * normally resolves it. But {@link #fail} can arrive having never seen
 * {@link #start} at all (every retry attempt failed before the upstream
 * ever answered — see {@code ResponseSink}'s Javadoc), and failing only
 * {@link #data} in that case leaves {@link #started} unresolved forever: the
 * caller's connection hangs with no response ever begun, rather than
 * receiving an error. {@link #fail} must therefore also fail {@link
 * #started} — harmless if {@link #start} already resolved it, since a
 * {@code UniEmitter} silently drops a second terminal signal.
 */
public final class StreamingResponseSink implements ResponseSink {

    private final UnicastProcessor<Buffer> data = UnicastProcessor.create();
    private final UniEmitter<? super StreamStart> started;
    private final AtomicBoolean startedResolved = new AtomicBoolean(false);

    public StreamingResponseSink(UniEmitter<? super StreamStart> started) {
        this.started = started;
    }

    @Override
    public void start(String contentType) {
        startedResolved.set(true);
        started.complete(new StreamStart(contentType, data));
    }

    @Override
    public void emit(byte[] chunk) {
        data.onNext(Buffer.buffer(chunk));
    }

    @Override
    public void complete() {
        data.onComplete();
    }

    @Override
    public void fail(Throwable cause) {
        if (startedResolved.compareAndSet(false, true)) {
            started.fail(cause);
        }
        data.onError(cause);
    }
}
