package com.scivicslab.gpubroker.response;

import java.time.Instant;

/**
 * One {@code jobId}'s entry in the {@code JobResultStore}: its status, and —
 * once {@code DONE} — the real AI service's response body and {@code
 * Content-Type} (carried as a field of this JSON envelope, not as the GET
 * response's own HTTP header, since the client already has to parse this
 * envelope to reach {@link #body} at all).
 */
public record JobResult(Status status, byte[] body, String contentType, String error, Instant createdAt) {

    public enum Status {
        PENDING, DONE, FAILED
    }

    public static JobResult pending() {
        return new JobResult(Status.PENDING, null, null, null, Instant.now());
    }

    /** Same {@link #createdAt}, now carrying the finished result. */
    public JobResult done(byte[] body, String contentType) {
        return new JobResult(Status.DONE, body, contentType, null, createdAt);
    }

    /** Same {@link #createdAt}, now carrying the failure. */
    public JobResult failed(Throwable t) {
        return new JobResult(Status.FAILED, null, null, t.getMessage(), createdAt);
    }
}
