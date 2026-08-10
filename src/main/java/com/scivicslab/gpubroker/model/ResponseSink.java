package com.scivicslab.gpubroker.model;

/**
 * Where an {@code AiServiceEndpoint} (via {@code AiServiceClient}) writes the
 * response it gets back from the real AI service. Two implementations exist:
 * one that streams straight into the original HTTP connection, and one that
 * buffers into a {@code JobResultStore} entry for later polling. Neither the
 * {@code JobQueue} nor the {@code AiServiceEndpoint} knows which one it holds.
 *
 * <p>Call order is always {@code start} once, then any number of {@code emit}
 * calls, then exactly one of {@code complete} or {@code fail}.
 */
public interface ResponseSink {

    /** The AiServiceEndpoint's real Content-Type, reported once, before any emit. */
    void start(String contentType);

    /** One chunk of the AiServiceEndpoint's response body. */
    void emit(byte[] chunk);

    /** The AiServiceEndpoint's response finished successfully. */
    void complete();

    /** The AiServiceEndpoint's response ended in failure. */
    void fail(Throwable cause);
}
