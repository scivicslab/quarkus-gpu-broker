package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.gpubroker.actor.JobQueue;

/**
 * The submission budget counts a submitter's queued jobs, so the slot has to come back the
 * moment the job leaves the deque — see {@code BackgroundJobAdmissionControl_260820_oo01}.
 */
@DisplayName("AdmissionReleasingResponseSink — the budget bounds the queue, not the work in flight")
class AdmissionReleasingResponseSinkTest {

    private static Job job(Priority priority, AdmissionReleasingResponseSink sink) {
        return Job.first(new RequestBody("x".getBytes(StandardCharsets.UTF_8), "text/plain"), priority, sink);
    }

    @Test
    void dispatched_releasesTheSlot() {
        AtomicInteger releases = new AtomicInteger();
        AdmissionReleasingResponseSink sink =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);

        sink.dispatched();

        assertEquals(1, releases.get());
    }

    /** A dispatched job completes later; that must not release a second time. */
    @Test
    void dispatchedThenCompleted_releasesOnlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        AdmissionReleasingResponseSink sink =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);

        sink.dispatched();
        sink.start("text/plain");
        sink.complete();

        assertEquals(1, releases.get());
    }

    /** A job drained at shutdown never reaches a worker, and still has to give its slot back. */
    @Test
    void failedWithoutEverBeingDispatched_stillReleases() {
        AtomicInteger releases = new AtomicInteger();
        AdmissionReleasingResponseSink sink =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);

        sink.fail(new IllegalStateException("draining"));

        assertEquals(1, releases.get());
    }

    /** A retried job is dispatched again on the same sink; the slot is already back. */
    @Test
    void dispatchedTwiceByRetry_releasesOnlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        AdmissionReleasingResponseSink sink =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);

        sink.dispatched();
        sink.dispatched();

        assertEquals(1, releases.get());
    }

    /** Every call still reaches the wrapped sink unchanged. */
    @Test
    void delegatesEveryCall() {
        RecordingResponseSink delegate = new RecordingResponseSink();
        AdmissionReleasingResponseSink sink = new AdmissionReleasingResponseSink(delegate, () -> { });

        sink.start("application/json");
        sink.emit("hi".getBytes(StandardCharsets.UTF_8));
        sink.complete();

        assertEquals("application/json", delegate.contentType());
        assertEquals("hi", delegate.bodyAsString());
        assertTrue(delegate.isCompleted());
    }

    /**
     * The budget is not consumed by jobs that are running: a job handed straight to an idle
     * worker gives its slot back at once, so a submitter with a budget of 1 can keep as many
     * workers busy as the queue has.
     */
    @Test
    void jobDispatchedToAnIdleWorker_releasesBeforeItRuns() {
        AtomicInteger releases = new AtomicInteger();
        JobQueue queue = new JobQueue();
        queue.attach("e1");

        AdmissionReleasingResponseSink sink =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);
        queue.submit(job(Priority.BACKGROUND, sink));

        assertEquals(1, releases.get(), "left the deque, so it no longer counts against the budget");
        assertEquals(0, queue.snapshot().pendingCount());
    }

    /** A job that stays queued keeps its slot, because it is exactly what the budget bounds. */
    @Test
    void jobLeftInTheDeque_keepsItsSlot() {
        AtomicInteger releases = new AtomicInteger();
        JobQueue queue = new JobQueue();
        queue.attach("e1");
        queue.submit(job(Priority.BACKGROUND, new AdmissionReleasingResponseSink(
                new RecordingResponseSink(), () -> { })));   // occupies the only worker

        AdmissionReleasingResponseSink queued =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);
        queue.submit(job(Priority.BACKGROUND, queued));

        assertEquals(0, releases.get());
        assertEquals(1, queue.snapshot().pendingCount());

        queue.requestWork("e1");   // the worker finishes and takes the queued job

        assertEquals(1, releases.get());
        assertEquals(0, queue.snapshot().pendingCount());
    }

    /** Draining takes jobs out of the deque without dispatching them. */
    @Test
    void drainPending_doesNotReportADispatch() {
        AtomicInteger releases = new AtomicInteger();
        JobQueue queue = new JobQueue();
        queue.attach("e1");
        queue.submit(job(Priority.BACKGROUND, new AdmissionReleasingResponseSink(
                new RecordingResponseSink(), () -> { })));
        AdmissionReleasingResponseSink queued =
                new AdmissionReleasingResponseSink(new RecordingResponseSink(), releases::incrementAndGet);
        queue.submit(job(Priority.BACKGROUND, queued));

        queue.drainPending();

        assertEquals(0, releases.get(), "not dispatched; the shutdown path fails it instead");
        assertFalse(queue.snapshot().pendingCount() > 0);
    }
}
