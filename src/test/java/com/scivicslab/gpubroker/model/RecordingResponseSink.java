package com.scivicslab.gpubroker.model;

import java.io.ByteArrayOutputStream;

/** Test double: records every call so a test can assert on what a job's response looked like. */
public final class RecordingResponseSink implements ResponseSink {

    private String contentType;
    private int status;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private boolean completed;
    private Throwable failure;

    @Override
    public void start(String contentType, int status) {
        this.contentType = contentType;
        this.status = status;
    }

    public int status() {
        return status;
    }

    @Override
    public void emit(byte[] chunk) {
        body.writeBytes(chunk);
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public void fail(Throwable cause) {
        failure = cause;
    }

    public String contentType() {
        return contentType;
    }

    public String bodyAsString() {
        return body.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failure != null;
    }

    public Throwable failure() {
        return failure;
    }
}
