package com.scivicslab.gpubroker.rest;

import java.util.concurrent.atomic.AtomicBoolean;

import com.scivicslab.gpubroker.model.ResponseSink;

/**
 * Delegates every call to the wrapped {@code ResponseSink} unchanged, and
 * additionally releases the {@link SubmissionAdmissionControl} slot reserved
 * for this job the moment the job leaves {@code JobQueue}'s deque ({@link
 * #dispatched}) — not when it finishes.
 *
 * <p>The budget bounds how long a submitter's queue may grow, so it must stop
 * counting a job once that job is no longer queued. Holding the slot until
 * completion would make the budget bound queued *and* running jobs together,
 * capping how many workers one submitter can occupy — with more workers than
 * the budget, some could never be used at all (see {@code
 * BackgroundJobAdmissionControl_260820_oo01}).
 *
 * <p>{@link #complete} and {@link #fail} still release, for the job that never
 * reached a worker: {@code JobQueueRegistry}'s shutdown drain fails everything
 * left in the deque. {@link #released} makes the release happen exactly once,
 * since a job that was dispatched also completes or fails afterwards, and a
 * retried job ({@code AiServiceEndpointWorker.requeue}) is dispatched more than
 * once on the same sink.
 */
final class AdmissionReleasingResponseSink implements ResponseSink {

    private final ResponseSink delegate;
    private final Runnable onRelease;
    private final AtomicBoolean released = new AtomicBoolean(false);

    AdmissionReleasingResponseSink(ResponseSink delegate, Runnable onRelease) {
        this.delegate = delegate;
        this.onRelease = onRelease;
    }

    @Override
    public void dispatched() {
        delegate.dispatched();
        release();
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
        release();
    }

    @Override
    public void fail(Throwable cause) {
        delegate.fail(cause);
        release();
    }

    private void release() {
        if (released.compareAndSet(false, true)) {
            onRelease.run();
        }
    }
}
