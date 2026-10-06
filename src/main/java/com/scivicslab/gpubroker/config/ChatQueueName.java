package com.scivicslab.gpubroker.config;

/**
 * Derives the {@code queueName} an OpenAI-compatible chat model is registered under, from its model
 * id. Shared by {@link VllmChatProbe} (deriving the queue name at discovery time from a probed model
 * id) and {@code OpenAiCompatResource} (deriving it at request time from a client-supplied {@code
 * "model"} field) so the two stay byte-for-byte in sync within this one artifact -- unlike {@code
 * gpu-broker-client}'s {@code QueueNames}, which necessarily duplicates this rule across an artifact
 * boundary.
 */
public final class ChatQueueName {

    /**
     * The prefix every chat queue carries. It names the kind of service, not the server that
     * implements it: vLLM, TensorFold and Strata all answer the same OpenAI chat API and all land
     * here. The prefix used to be {@code vllm-}, from the days when vLLM was the only such server,
     * which made {@code vllm-qwen3.8-flash-next-tensorfold} name two runtimes at once.
     */
    public static final String PREFIX = "chat-";

    /** What queues were called before {@link #PREFIX}; see {@link #resolve}. */
    static final String LEGACY_PREFIX = "vllm-";

    private ChatQueueName() {
    }

    /**
     * A model id such as {@code google/gemma-4-26B-A4B-it} is not a safe single
     * {@code /queue/{queueName}} path segment as-is -- the {@code /} would split it into two
     * segments. Replace anything outside the URL path-segment-safe unreserved set with {@code -}.
     */
    public static String of(String modelId) {
        return PREFIX + modelId.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /**
     * The current name of a queue a caller asked for. A client built against {@code
     * gpu-broker-client} 0.1.0 computes the old {@code vllm-} name, and there is no way for it to
     * learn otherwise until it is rebuilt; such a name is answered as the {@code chat-} queue of the
     * same model rather than with a 404. Every other name is returned unchanged, so a queue whose
     * own name begins with {@code vllm-} -- there is none, but a future probe could make one --
     * still resolves to itself first.
     */
    public static String resolve(String queueName) {
        return queueName != null && queueName.startsWith(LEGACY_PREFIX)
                ? PREFIX + queueName.substring(LEGACY_PREFIX.length())
                : queueName;
    }
}
