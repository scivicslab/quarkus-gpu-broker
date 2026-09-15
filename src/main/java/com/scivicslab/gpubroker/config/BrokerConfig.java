package com.scivicslab.gpubroker.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import io.smallrye.config.ConfigMapping;

/**
 * {@code broker.*} configuration: which physical nodes to probe, and
 * per-host:port overrides/declarations of capability ({@code
 * CapabilityConfig_260810_oo01}).
 *
 * <p>{@code capabilities} is a repeating, multi-attribute-per-key structure
 * (host:port -> several fields), which is why this uses {@code
 * @ConfigMapping} rather than individual {@code @ConfigProperty} fields —
 * see {@code CapabilityConfig_260810_oo01} "なぜ @ConfigMapping へ切り替えたか".
 */
@ConfigMapping(prefix = "broker")
public interface BrokerConfig {

    /** Physical compute node IPs/CIDR blocks to probe. Empty if unset. */
    Optional<List<String>> nodes();

    /** Per host:port capability overrides/declarations, keyed by "host:port". Empty if unset. */
    Map<String, EndpointCapability> capabilities();

    /**
     * Per-queue limits on one reply's generation, keyed by queue name (e.g.
     * {@code vllm-google-gemma-4-26B-A4B-it}). Empty if unset.
     *
     * <p>Keyed by queue rather than by host:port because these are properties of the model, not of
     * the machine serving it: a context length of 131,072 belongs to {@code gemma-4}, and the two
     * nodes that serve it must not be given different caps
     * ({@code RunawayGenerationLimits_260915_oo01}).</p>
     */
    Map<String, QueueGenerationLimit> generationLimits();

    /** The key an entry uses to stand for every queue that has no entry of its own. */
    String ANY_QUEUE = "*";

    /**
     * This queue's limits: its own entry, or the one written under {@link #ANY_QUEUE} when it has
     * none. {@code null} when neither exists.
     *
     * <p>A queue is discovered by probing and a limit is written by hand, so a model can be serving
     * traffic before anyone has given it a ceiling -- which is how {@code Cosmos3-Nano} appeared
     * carrying a context length of 262,144 and no limit at all. The default is what covers the gap
     * between a model appearing and someone noticing it.</p>
     *
     * <p>An entry of its own replaces the default rather than being merged into it: a queue that
     * names one field and inherits another would make the effective limit something a reader has
     * to assemble from two places.</p>
     */
    default QueueGenerationLimit generationLimitFor(String queueName) {
        QueueGenerationLimit own = generationLimits().get(queueName);
        return own != null ? own : generationLimits().get(ANY_QUEUE);
    }

    interface QueueGenerationLimit {

        /**
         * The most tokens one reply may generate. Sent as {@code max_tokens}: added when the client
         * asked for no cap, and lowered to this when the client asked for a larger one.
         *
         * <p>Without it a reply ends only when the model emits its end-of-reply token or the
         * context length runs out, which is how one degenerate loop held a GPU for thirty
         * minutes.</p>
         */
        OptionalInt maxTokens();

        /**
         * Sent as {@code repetition_penalty} when the client named none.
         *
         * <p>Left unset by default: vLLM's {@code repetition_penalty} penalises tokens that appear
         * in the prompt as well as in the generated text, so a task that rewrites a document it was
         * given is pushed away from the document's own words.</p>
         */
        OptionalDouble repetitionPenalty();

        /**
         * Sent as {@code frequency_penalty} when the client named none. Unlike
         * {@link #repetitionPenalty}, this counts only what has been generated.
         */
        OptionalDouble frequencyPenalty();
    }

    interface EndpointCapability {

        /** Overrides {@code EndpointProbe.defaultMaxConcurrency()} for this one instance. Used by {@code Builder}. */
        OptionalInt maxConcurrency();

        /** Operator-declared, display-only — not verified against the real service. */
        OptionalInt maxContextLength();

        /** Operator-declared, display-only — not verified against the real service. */
        Optional<Boolean> thinkingModeSupported();

        /** Operator-declared, display-only — not verified against the real service. */
        Optional<Boolean> toolCallingSupported();
    }
}
