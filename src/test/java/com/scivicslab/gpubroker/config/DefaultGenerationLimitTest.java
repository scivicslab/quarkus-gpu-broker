package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * A queue is discovered by probing and its ceiling is written by hand, so a model can be serving
 * traffic before anyone has given it one -- which is how {@code Cosmos3-Nano} turned up with a
 * context length of 262,144 and no limit ({@code RunawayGenerationLimits_260915_oo01}).
 */
class DefaultGenerationLimitTest {

    private record Limit(OptionalInt maxTokens, OptionalDouble repetitionPenalty,
                         OptionalDouble frequencyPenalty) implements BrokerConfig.QueueGenerationLimit {

        static Limit ofMaxTokens(int value) {
            return new Limit(OptionalInt.of(value), OptionalDouble.empty(), OptionalDouble.empty());
        }
    }

    /** Only the one method under test carries anything; the rest is what a config mapping requires. */
    private record Config(Map<String, QueueGenerationLimit> generationLimits) implements BrokerConfig {
        @Override public Optional<List<String>> nodes() { return Optional.empty(); }
        @Override public Map<String, EndpointCapability> capabilities() { return Map.of(); }
    }

    @Test
    void aQueueWithNoEntryOfItsOwn_takesTheDefault() {
        BrokerConfig config = new Config(Map.of(BrokerConfig.ANY_QUEUE, Limit.ofMaxTokens(8192)));

        assertEquals(8192, config.generationLimitFor("vllm-something-nobody-listed").maxTokens().getAsInt());
    }

    @Test
    void anEntryOfItsOwn_replacesTheDefault() {
        BrokerConfig config = new Config(Map.of(
                BrokerConfig.ANY_QUEUE, Limit.ofMaxTokens(8192),
                "vllm-small", Limit.ofMaxTokens(4096)));

        assertEquals(4096, config.generationLimitFor("vllm-small").maxTokens().getAsInt());
    }

    @Test
    void noEntryAndNoDefault_isNoLimit() {
        assertNull(new Config(Map.of()).generationLimitFor("vllm-anything"));
    }
}
