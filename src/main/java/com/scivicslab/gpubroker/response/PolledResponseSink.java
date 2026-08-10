package com.scivicslab.gpubroker.response;

import java.io.ByteArrayOutputStream;

import com.scivicslab.gpubroker.model.ResponseSink;

/**
 * The submit-then-poll {@code ResponseSink}: buffers the real AI service's
 * response until it finishes, then writes it into a {@code JobResultStore}
 * entry for a later {@code GET} to retrieve. Never streams to an open HTTP
 * connection, so there is no partial-write-then-retry hazard the way there
 * is for {@code StreamingResponseSink} — a non-streaming AI service's
 * response arrives as a single body, so {@link #emit} is called at most
 * once before {@link #complete} or {@link #fail}.
 */
public final class PolledResponseSink implements ResponseSink {

    private final String jobId;
    private final JobResultStore results;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private String contentType;

    public PolledResponseSink(String jobId, JobResultStore results) {
        this.jobId = jobId;
        this.results = results;
    }

    @Override
    public void start(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public void emit(byte[] chunk) {
        buffer.writeBytes(chunk);
    }

    @Override
    public void complete() {
        results.complete(jobId, buffer.toByteArray(), contentType);
    }

    @Override
    public void fail(Throwable cause) {
        results.fail(jobId, cause);
    }
}
