package com.scivicslab.gpubroker.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.gpubroker.actor.AiServiceEndpointWorker;
import com.scivicslab.gpubroker.actor.EmbeddingEndpointWorker;
import com.scivicslab.gpubroker.actor.MarkerOcrEndpointWorker;
import com.scivicslab.gpubroker.actor.VllmChatEndpointWorker;
import com.scivicslab.gpubroker.actor.YomiTokuOcrEndpointWorker;
import com.scivicslab.gpubroker.llm.AiServiceClient;

/**
 * Every AI service kind the broker knows how to discover and talk to. Each
 * constant carries the things needed to probe for it (conventional port,
 * probe path) and, as constant-specific bodies, the things that genuinely
 * vary in behavior rather than data: which {@code AiServiceEndpointWorker}
 * subclass to build once a probe succeeds, how to derive that endpoint's
 * {@code queueName} from the probe response, and a default concurrency
 * ceiling for that kind (overridable per deployment — see {@code
 * 018_concurrency_control/000_PerEndpointConcurrency_260810_oo01}).
 *
 * <p>{@code queueName} derivation lives here, not on {@code
 * AiServiceEndpointWorker}, because it has to run before an endpoint
 * instance exists — {@code queueName} is one of that instance's own
 * constructor arguments.
 */
public enum EndpointKind {

    VLLM_CHAT(8000, "/v1/models") {
        @Override
        public AiServiceEndpointWorker createWorker(String queueName, String address, AiServiceClient client) {
            return new VllmChatEndpointWorker(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "vllm-" + sanitizeForPathSegment(extractModelName(probeResponseBody));
        }

        @Override
        public int defaultMaxConcurrency() {
            return 32;   // safety bound, not a performance-tuned value — vLLM's own KV cache admits/queues internally
        }
    },
    YOMITOKU_OCR(8013, "/") {
        @Override
        public AiServiceEndpointWorker createWorker(String queueName, String address, AiServiceClient client) {
            return new YomiTokuOcrEndpointWorker(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "yomitoku-ocr";
        }

        @Override
        public int defaultMaxConcurrency() {
            return 1;   // no measured benefit on the currently deployed GPU — not a proven architectural ceiling
        }
    },
    MARKER_OCR(8001, "/") {
        @Override
        public AiServiceEndpointWorker createWorker(String queueName, String address, AiServiceClient client) {
            return new MarkerOcrEndpointWorker(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "marker-ocr";
        }

        @Override
        public int defaultMaxConcurrency() {
            return 1;   // measured intermittent 500 under 2 concurrent requests — a reliability constraint, not tuning
        }
    },
    EMBEDDING(8012, "/") {
        @Override
        public AiServiceEndpointWorker createWorker(String queueName, String address, AiServiceClient client) {
            return new EmbeddingEndpointWorker(queueName, address, client);
        }

        @Override
        public String deriveQueueName(String probeResponseBody) {
            return "embedding-e5large";
        }

        @Override
        public int defaultMaxConcurrency() {
            return 8;   // measured 30 concurrent with no errors; kept conservative below that
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

    public abstract AiServiceEndpointWorker createWorker(String queueName, String address, AiServiceClient client);

    public abstract String deriveQueueName(String probeResponseBody);

    /** Default {@code maxConcurrency} for this kind; deployments may override via {@code broker.max-concurrency.<KIND>}. */
    public abstract int defaultMaxConcurrency();

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
