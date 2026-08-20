package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.model.ResponseSink;

/**
 * Delegates every call to the wrapped {@code ResponseSink} unchanged, and
 * additionally releases the {@link SubmissionAdmissionControl} slot
 * reserved for this job once it reaches a terminal state ({@link #complete}
 * or {@link #fail}) — see {@code BackgroundJobAdmissionControl_260820_oo01}.
 */
final class AdmissionReleasingResponseSink implements ResponseSink {

    private final ResponseSink delegate;
    private final Runnable onTerminal;

    AdmissionReleasingResponseSink(ResponseSink delegate, Runnable onTerminal) {
        this.delegate = delegate;
        this.onTerminal = onTerminal;
    }

    @Override
    public void start(String contentType) {
        delegate.start(contentType);
    }

    @Override
    public void emit(byte[] chunk) {
        delegate.emit(chunk);
    }

    @Override
    public void complete() {
        delegate.complete();
        onTerminal.run();
    }

    @Override
    public void fail(Throwable cause) {
        delegate.fail(cause);
        onTerminal.run();
    }
}
