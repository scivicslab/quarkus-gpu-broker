package com.scivicslab.gpubroker.model;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A single inference request flowing through the broker.
 *
 * <p>A job carries its identity and {@link Priority} (used by the priority deque,
 * S_base/S_dispatch/S_health) and, for the proxy front (S_proxy), the OpenAI
 * request body to forward, the {@link ResponseSink} to relay the upstream
 * response into, and a {@code completion} future. The future is completed once
 * the relay sees the end of the upstream response ({@code [DONE]} for a stream),
 * which both releases the waiting request thread and lets the {@code NodeActor}
 * pull the next job.
 *
 * <p>Identity-only jobs ({@link #fg}/{@link #bg}) leave {@code requestBody} and
 * {@code sink} null; they exist for the deque/dispatch unit tests.
 *
 * <p>{@code contentType} is completed by the node once it has the upstream
 * response headers, so the proxy front can mirror the upstream {@code
 * Content-Type} (e.g. {@code application/json} vs {@code text/event-stream})
 * onto the client response before streaming the body.
 */
public record Job(String id, Priority priority, String requestBody, ResponseSink sink,
                  CompletableFuture<Void> completion, CompletableFuture<String> contentType) {

    /** Foreground identity job (no body/sink) — for deque/dispatch tests. */
    public static Job fg(String id) {
        return new Job(id, Priority.FOREGROUND, null, null, new CompletableFuture<>(), new CompletableFuture<>());
    }

    /** Background identity job (no body/sink) — for deque/dispatch tests. */
    public static Job bg(String id) {
        return new Job(id, Priority.BACKGROUND, null, null, new CompletableFuture<>(), new CompletableFuture<>());
    }

    /** Background streaming job with a sink — for relay tests. */
    public static Job bgStreaming(ResponseSink sink) {
        return new Job(newId(), Priority.BACKGROUND, null, sink, new CompletableFuture<>(), new CompletableFuture<>());
    }

    /** Real request: OpenAI body to forward, sink to relay into, and a priority. */
    public static Job request(String requestBody, ResponseSink sink, Priority priority) {
        return new Job(newId(), priority, requestBody, sink, new CompletableFuture<>(), new CompletableFuture<>());
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Mark this job finished. Idempotent: the first call wins (e.g. at {@code [DONE]}). */
    public void complete() {
        completion.complete(null);
    }

    public boolean isCompleted() {
        return completion.isDone();
    }

    /** Block the caller until this job's relay has completed. */
    public void awaitCompletion() {
        completion.join();
    }

    /** Report the upstream Content-Type once the node has the response headers. Idempotent. */
    public void completeContentType(String value) {
        contentType.complete(value);
    }

    /** Block until a node reports the upstream Content-Type, then return it. */
    public String awaitContentType() {
        return contentType.join();
    }
}
