package com.scivicslab.gpubroker.config;

/**
 * Derives the {@code queueName} a vLLM-backed model is registered under, from its model id. Shared
 * by {@link VllmChatProbe} (deriving the queue name at discovery time from a probed model id) and
 * {@code OpenAiCompatResource} (deriving it at request time from a client-supplied {@code "model"}
 * field) so the two stay byte-for-byte in sync within this one artifact -- unlike {@code
 * gpu-broker-client}'s {@code QueueNames.vllmChat}, which necessarily duplicates this rule across
 * an artifact boundary.
 */
public final class VllmQueueName {

    private VllmQueueName() {
    }

    /**
     * A model id such as {@code google/gemma-4-26B-A4B-it} is not a safe single
     * {@code /queue/{queueName}} path segment as-is -- the {@code /} would split it into two
     * segments. Replace anything outside the URL path-segment-safe unreserved set with {@code -}.
     */
    public static String of(String modelId) {
        return "vllm-" + modelId.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
