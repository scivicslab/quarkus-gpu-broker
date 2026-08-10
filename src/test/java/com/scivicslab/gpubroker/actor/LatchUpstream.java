package com.scivicslab.gpubroker.actor;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.gpubroker.llm.UpstreamLlmClient;
import com.scivicslab.gpubroker.model.Job;

/**
 * Test stub for {@link UpstreamLlmClient}. Each {@code send} blocks on a
 * per-job latch (released by {@link #complete}) so a test can hold nodes
 * "in flight" and observe completion-driven dispatch deterministically.
 *
 * <p>It also tracks the maximum number of concurrent {@code send} calls observed
 * for any single node URL, so a test can assert N=1.
 */
final class LatchUpstream implements UpstreamLlmClient {

    /** Counts down the instant a job's send begins. */
    private final Map<String, CountDownLatch> startSignal = new ConcurrentHashMap<>();
    /** Blocks a job's send until complete(jobId) is called. */
    private final Map<String, CountDownLatch> release = new ConcurrentHashMap<>();
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> inFlightPerNode = new ConcurrentHashMap<>();
    private final AtomicInteger maxConcurrentPerNode = new AtomicInteger(0);

    private CountDownLatch startSignalFor(String jobId) {
        return startSignal.computeIfAbsent(jobId, k -> new CountDownLatch(1));
    }

    private CountDownLatch releaseFor(String jobId) {
        return release.computeIfAbsent(jobId, k -> new CountDownLatch(1));
    }

    @Override
    public void send(String url, Job job) {
        String jobId = job.id();
        CountDownLatch hold = releaseFor(jobId);
        AtomicInteger inFlight = inFlightPerNode.computeIfAbsent(url, k -> new AtomicInteger());
        int now = inFlight.incrementAndGet();
        maxConcurrentPerNode.accumulateAndGet(now, Math::max);
        started.add(jobId);
        startSignalFor(jobId).countDown();
        try {
            hold.await();                       // block until the test completes this job
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** Block until the given job's send has begun. */
    void awaitStarted(String jobId) throws InterruptedException {
        startSignalFor(jobId).await();
    }

    boolean isStarted(String jobId) {
        return started.contains(jobId);
    }

    /** Release one in-flight job so its node completes and pulls the next. */
    void complete(String jobId) {
        releaseFor(jobId).countDown();
    }

    /** Release every job (cleanup so actors can finish and the system terminate). */
    void completeAll() {
        release.values().forEach(CountDownLatch::countDown);
    }

    int maxConcurrentPerNode() {
        return maxConcurrentPerNode.get();
    }
}
