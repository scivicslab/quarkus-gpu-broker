package com.scivicslab.gpubroker.config;

import java.util.Optional;
import java.util.OptionalInt;

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
        return extractModelName(probeResponseBody).map(ChatQueueName::of);
    }

    /** The true, unsanitized model id (e.g. {@code google/gemma-4-26B-A4B-it}) -- see {@code
     *  OpenAiCompatFacade_260822_oo01} "なぜ表示名にサニタイズ前のモデルIDが要るか". */
    @Override
    public Optional<String> deriveDisplayName(String probeResponseBody) {
        return extractModelName(probeResponseBody);
    }

    /**
     * The window the server reported. The three servers behind this one probe each put it
     * somewhere else: vLLM in {@code data[0].max_model_len}, Strata in {@code data[0].meta.n_ctx},
     * and TensorFold nowhere at all (its {@code /v1/models} carries the id and nothing else), which
     * is what the declared fallback in {@link EndpointProbe} is for.
     */
    @Override
    public OptionalInt deriveContextLength(String probeResponseBody) {
        try {
            JsonNode first = MAPPER.readTree(probeResponseBody).path("data").path(0);
            for (JsonNode candidate : new JsonNode[] {first.path("max_model_len"),
                                                      first.path("meta").path("n_ctx")}) {
                if (candidate.isIntegralNumber() && candidate.asLong() > 0) {
                    return OptionalInt.of(candidate.asInt());
                }
            }
            return OptionalInt.empty();
        } catch (JsonProcessingException e) {
            return OptionalInt.empty();
        }
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
