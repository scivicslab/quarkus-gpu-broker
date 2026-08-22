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
        return extractModelName(probeResponseBody).map(VllmQueueName::of);
    }

    /** The true, unsanitized model id (e.g. {@code google/gemma-4-26B-A4B-it}) -- see {@code
     *  OpenAiCompatFacade_260822_oo01} "なぜ表示名にサニタイズ前のモデルIDが要るか". */
    @Override
    public Optional<String> deriveDisplayName(String probeResponseBody) {
        return extractModelName(probeResponseBody);
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
}
