package com.scivicslab.gpubroker.actor;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.scivicslab.gpubroker.llm.AiServiceCallException;
import com.scivicslab.gpubroker.llm.AiServiceClient;
import com.scivicslab.gpubroker.model.Job;

/**
 * Test stub for {@link AiServiceClient}. Each {@code send} blocks on a
 * per-job latch (released by {@link #complete}) so a test can hold
 * endpoints "in flight" and observe completion-driven dispatch
 * deterministically. Jobs are identified by their request body, decoded as
 * a plain label string (there is no separate job id in the real design).
 *
 * <p>Also tracks the maximum number of concurrent {@code send} calls
 * observed for any single address, so a test can assert N=1.
 */
final class LatchAiServiceClient implements AiServiceClient {

    private final Map<String, CountDownLatch> startSignal = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> release = new ConcurrentHashMap<>();
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    private final Set<String> failOnRelease = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> inFlightPerAddress = new ConcurrentHashMap<>();
    private final AtomicInteger maxConcurrentPerAddress = new AtomicInteger(0);

    static String label(Job job) {
        return new String(job.request().bytes(), StandardCharsets.UTF_8);
    }

    private CountDownLatch startSignalFor(String label) {
        return startSignal.computeIfAbsent(label, k -> new CountDownLatch(1));
    }

    private CountDownLatch releaseFor(String label) {
        return release.computeIfAbsent(label, k -> new CountDownLatch(1));
    }

    @Override
    public void send(String address, String path, Job job) {
        String label = label(job);
        CountDownLatch hold = releaseFor(label);
        AtomicInteger inFlight = inFlightPerAddress.computeIfAbsent(address, k -> new AtomicInteger());
        int now = inFlight.incrementAndGet();
        maxConcurrentPerAddress.accumulateAndGet(now, Math::max);
        started.add(label);
        startSignalFor(label).countDown();
        try {
            hold.await();   // block until the test completes this job
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
        if (failOnRelease.contains(label)) {
            throw new AiServiceCallException("stub failure for " + label);
        }
        job.responseSink().start("text/plain");
        job.responseSink().emit(label.getBytes(StandardCharsets.UTF_8));
        job.responseSink().complete();
    }

    /** Block until the given job's send has begun. */
    void awaitStarted(String label) throws InterruptedException {
        startSignalFor(label).await();
    }

    boolean isStarted(String label) {
        return started.contains(label);
    }

    /** Release one in-flight job so its endpoint completes and pulls the next. */
    void complete(String label) {
        releaseFor(label).countDown();
    }

    /** Release every job (cleanup so actors can finish and the system terminate). */
    void completeAll() {
        release.values().forEach(CountDownLatch::countDown);
    }

    /** The next release of this label raises {@link AiServiceCallException} instead of succeeding. */
    void failOnRelease(String label) {
        failOnRelease.add(label);
    }

    int maxConcurrentPerAddress() {
        return maxConcurrentPerAddress.get();
    }
}
