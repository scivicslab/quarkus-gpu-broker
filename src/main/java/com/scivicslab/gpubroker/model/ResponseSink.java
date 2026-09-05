package com.scivicslab.gpubroker.model;

/**
 * Where an {@code AiServiceEndpoint} (via {@code AiServiceClient}) writes the
 * response it gets back from the real AI service. Two implementations exist:
 * one that streams straight into the original HTTP connection, and one that
 * buffers into a {@code JobResultStore} entry for later polling. Neither the
 * {@code JobQueue} nor the {@code AiServiceEndpoint} knows which one it holds.
 *
 * <p>Call order is always {@code dispatched} once (if the job ever reaches a
 * worker at all), then {@code start} once, then any number of {@code emit}
 * calls, then exactly one of {@code complete} or {@code fail}.
 */
public interface ResponseSink {

    /**
     * This job has just been taken out of {@code JobQueue}'s deque and handed to an
     * {@code AiServiceEndpointWorker} — it is no longer queued, though it has not run yet.
     *
     * <p>Distinct from {@link #start}, which reports that the upstream service began
     * answering. Between the two, the job occupies a worker but no queue position.
     *
     * <p>Default no-op: only {@code AdmissionReleasingResponseSink} cares, because the
     * submission budget bounds the queue, not the work in flight (see {@code
     * BackgroundJobAdmissionControl_260820_oo01}). A job that is drained at shutdown never
     * receives this call.
     */
    default void dispatched() {
    }

    /** The AiServiceEndpoint's real Content-Type, reported once, before any emit. */
    void start(String contentType);

    /** One chunk of the AiServiceEndpoint's response body. */
    void emit(byte[] chunk);

    /** The AiServiceEndpoint's response finished successfully. */
    void complete();

    /** The AiServiceEndpoint's response ended in failure. */
    void fail(Throwable cause);
}
