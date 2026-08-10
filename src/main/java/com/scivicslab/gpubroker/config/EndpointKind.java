package com.scivicslab.gpubroker.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.gpubroker.actor.AiServiceEndpoint;
import com.scivicslab.gpubroker.actor.EmbeddingEndpoint;
import com.scivicslab.gpubroker.actor.MarkerOcrEndpoint;
import com.scivicslab.gpubroker.actor.VllmChatEndpoint;
import com.scivicslab.gpubroker.actor.YomiTokuOcrEndpoint;
import com.scivicslab.gpubroker.llm.AiServiceClient;

/**
 * Every AI service kind the broker knows how to discover and talk to. Each
 * constant carries the two things needed to probe for it (conventional
 * port, probe path) and, as constant-specific bodies, the two things that
 * genuinely vary in behavior rather than data: which {@code
 * AiServiceEndpoint} subclass to build once a probe succeeds, and how to
 * derive that endpoint's {@code queueName} from the probe response.
 *
 * <p>{@code queueName} derivation lives here, not on {@code AiServiceEndpoint},
 * because it has to run before an endpoint instance exists — {@code
 * queueName} is one of that instance's own constructor arguments.
 */
public enum EndpointKind {

    VLLM_CHAT(8000, "/v1/models") {
        @Override
        public AiServiceEndpoint createEndpoint(String queueName, String address, AiServiceClient client) {
            return new VllmChatEndpoint(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "vllm-" + sanitizeForPathSegment(extractModelName(probeResponseBody));
        }
    },
    YOMITOKU_OCR(8013, "/health") {
        @Override
        public AiServiceEndpoint createEndpoint(String queueName, String address, AiServiceClient client) {
            return new YomiTokuOcrEndpoint(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "yomitoku-ocr";
        }
    },
    MARKER_OCR(8001, "/health") {
        @Override
        public AiServiceEndpoint createEndpoint(String queueName, String address, AiServiceClient client) {
            return new MarkerOcrEndpoint(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "marker-ocr";
        }
    },
    EMBEDDING(8012, "/health") {
        @Override
        public AiServiceEndpoint createEndpoint(String queueName, String address, AiServiceClient client) {
            return new EmbeddingEndpoint(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "embedding-e5large";
        }
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int conventionalPort;
    private final String probePath;

    EndpointKind(int conventionalPort, String probePath) {
        this.conventionalPort = conventionalPort;
        this.probePath = probePath;
    }

    public int conventionalPort() {
        return conventionalPort;
    }

    public String probePath() {
        return probePath;
    }

    public abstract AiServiceEndpoint createEndpoint(String queueName, String address, AiServiceClient client);

    public abstract String deriveQueueName(String probeResponseBody);

    /** Extract the first model id out of an OpenAI-compatible {@code /v1/models} response. */
    static String extractModelName(String probeResponseBody) {
        try {
            JsonNode root = MAPPER.readTree(probeResponseBody);
            JsonNode id = root.path("data").path(0).path("id");
            if (id.isMissingNode()) {
                throw new IllegalArgumentException("no data[0].id in /v1/models response: " + probeResponseBody);
            }
            return id.asText();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("malformed /v1/models response: " + probeResponseBody, e);
        }
    }

    /**
     * A model id such as {@code google/gemma-4-26B-A4B-it} is not a safe single
     * {@code /queue/{queueName}} path segment as-is — the {@code /} would split it into two
     * segments. Replace anything outside the URL path-segment-safe unreserved set with {@code -}.
     */
    static String sanitizeForPathSegment(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
