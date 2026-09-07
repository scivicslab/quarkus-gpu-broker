package com.scivicslab.gpubroker.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

@DisplayName("StreamingResponseSink — resolving the original HTTP response, with or without start()")
class StreamingResponseSinkTest {

    /**
     * The path {@code HttpAiServiceClient} exercises on every attempt of an eventually-exhausted
     * retry: a 5xx (or connection failure) response never calls {@code start} at all, by design
     * (see {@code HttpAiServiceClient}'s Javadoc) — so the final {@code fail}, from {@code
     * AiServiceEndpointWorker.requeue}, is the first and only signal the sink ever receives.
     */
    @Test
    void fail_beforeStart_failsTheOriginalResponseInsteadOfHangingForever() {
        RuntimeException cause = new RuntimeException("gave up after 3 attempts");
        UniAssertSubscriber<StreamStart> subscriber = subscribeTo(sink -> sink.fail(cause));

        subscriber.awaitFailure();
        assertEquals(cause, subscriber.getFailure());
    }

    /** The ordinary path: the upstream answered, so start() already resolved the response. */
    @Test
    void fail_afterStart_leavesTheAlreadyResolvedResponseAlone() {
        UniAssertSubscriber<StreamStart> subscriber = subscribeTo(sink -> {
            sink.start("application/json");
            sink.fail(new RuntimeException("stream broke mid-flight"));
        });

        subscriber.awaitItem();
        assertEquals("application/json", subscriber.getItem().contentType());
    }

    @Test
    void start_resolvesWithTheGivenContentType() {
        UniAssertSubscriber<StreamStart> subscriber = subscribeTo(sink -> sink.start("text/event-stream"));

        subscriber.awaitItem();
        assertEquals("text/event-stream", subscriber.getItem().contentType());
    }

    /** A second terminal signal on the same emitter must not raise — only the first counts. */
    @Test
    void fail_calledTwice_doesNotThrow() {
        assertTrue(true, "reaching this line without an exception is the assertion");
        subscribeTo(sink -> {
            sink.fail(new RuntimeException("first"));
            sink.fail(new RuntimeException("second"));
        }).awaitFailure();
    }

    private static UniAssertSubscriber<StreamStart> subscribeTo(java.util.function.Consumer<StreamingResponseSink> body) {
        Uni<StreamStart> started = Uni.createFrom().emitter(emitter -> body.accept(new StreamingResponseSink(emitter)));
        return started.subscribe().withSubscriber(UniAssertSubscriber.create());
    }
}
