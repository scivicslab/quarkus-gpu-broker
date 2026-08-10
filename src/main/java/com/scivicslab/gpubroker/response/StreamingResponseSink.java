package com.scivicslab.gpubroker.response;

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
 */
public final class StreamingResponseSink implements ResponseSink {

    private final UnicastProcessor<Buffer> data = UnicastProcessor.create();
    private final UniEmitter<? super StreamStart> started;

    public StreamingResponseSink(UniEmitter<? super StreamStart> started) {
        this.started = started;
    }

    @Override
    public void start(String contentType) {
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
        data.onError(cause);
    }
}
