package com.scivicslab.gpubroker.actor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.scivicslab.gpubroker.llm.UpstreamException;
import com.scivicslab.gpubroker.llm.UpstreamLlmClient;
import com.scivicslab.gpubroker.model.Job;

/**
 * Test stub for {@link UpstreamLlmClient} that completes instantly and records
 * which node URL ran each job. Nodes listed via {@link #failNode} throw
 * {@link UpstreamException} instead of completing, so a test can drive the
 * failure / re-submit path without a real vLLM.
 */
final class RecordingUpstream implements UpstreamLlmClient {

    private final List<String> nodesUsed = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failingNodes = ConcurrentHashMap.newKeySet();
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private int completed = 0;

    /** Mark a node URL as failing: its sends throw {@link UpstreamException}. */
    void failNode(String url) {
        failingNodes.add(url);
    }

    @Override
    public void send(String url, Job job) {
        attempted.add(url);
        if (failingNodes.contains(url)) {
            throw new UpstreamException("upstream down: " + url);
        }
        synchronized (lock) {
            nodesUsed.add(url);
            completed++;
            lock.notifyAll();
        }
    }

    /** Block until at least {@code n} jobs have completed. */
    void awaitCompleted(int n) throws InterruptedException {
        synchronized (lock) {
            while (completed < n) {
                lock.wait();
            }
        }
    }

    int completedCount() {
        synchronized (lock) {
            return completed;
        }
    }

    /** Whether a send was ever attempted against this node URL (success or failure). */
    boolean attempted(String url) {
        return attempted.contains(url);
    }

    List<String> nodesUsed() {
        synchronized (nodesUsed) {
            return new ArrayList<>(nodesUsed);
        }
    }
}
