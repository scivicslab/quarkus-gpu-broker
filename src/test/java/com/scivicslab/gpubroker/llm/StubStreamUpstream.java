package com.scivicslab.gpubroker.llm;

import java.util.List;

import com.scivicslab.gpubroker.model.Job;

/**
 * Test stub upstream that emits pre-canned lines through the real
 * {@link StreamRelay}, so the completion-detection logic is exercised without a
 * network or a real vLLM.
 */
final class StubStreamUpstream implements UpstreamLlmClient {

    private final List<String> lines;

    StubStreamUpstream(String... lines) {
        this.lines = List.of(lines);
    }

    @Override
    public void send(String url, Job job) {
        StreamRelay.pump(lines.iterator(), job);
    }
}
