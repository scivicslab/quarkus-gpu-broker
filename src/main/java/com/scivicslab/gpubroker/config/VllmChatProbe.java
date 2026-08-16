package com.scivicslab.gpubroker.config;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/** vLLM's OpenAI-compatible chat completions endpoint. */
@ApplicationScoped
public class VllmChatProbe implements EndpointProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public int conventionalPort() {
        return 8000;
    }

    @Override
    public String probePath() {
        return "/v1/models";
    }

    @Override
    public String requestPath() {
        return "/v1/chat/completions";
    }

    @Override
    public int defaultMaxConcurrency() {
        return 32;   // safety bound, not a performance-tuned value — vLLM's own KV cache admits/queues internally
    }

    @Override
    public Optional<String> deriveQueueName(String probeResponseBody) {
        return extractModelName(probeResponseBody).map(id -> "vllm-" + sanitizeForPathSegment(id));
    }

    /** Extract the first model id out of an OpenAI-compatible /v1/models response. Empty if the shape doesn't match. */
    private static Optional<String> extractModelName(String probeResponseBody) {
        try {
            JsonNode root = MAPPER.readTree(probeResponseBody);
            JsonNode id = root.path("data").path(0).path("id");
            return id.isMissingNode() ? Optional.empty() : Optional.of(id.asText());
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * A model id such as {@code google/gemma-4-26B-A4B-it} is not a safe single
     * {@code /queue/{queueName}} path segment as-is — the {@code /} would split it into two
     * segments. Replace anything outside the URL path-segment-safe unreserved set with {@code -}.
     */
    private static String sanitizeForPathSegment(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
