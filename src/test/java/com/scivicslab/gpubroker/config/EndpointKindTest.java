package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.actor.EmbeddingEndpoint;
import com.scivicslab.gpubroker.actor.MarkerOcrEndpoint;
import com.scivicslab.gpubroker.actor.VllmChatEndpoint;
import com.scivicslab.gpubroker.actor.YomiTokuOcrEndpoint;
import com.scivicslab.gpubroker.llm.AiServiceClient;

@DisplayName("EndpointKind — per-kind queueName derivation and endpoint construction")
class EndpointKindTest {

    private static final AiServiceClient NOOP_CLIENT = (address, path, job) -> {
    };

    @Test
    void vllmChat_derivesQueueNameFromModelsResponse() {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4\",\"object\":\"model\"}]}";

        assertEquals("vllm-gemma-4", EndpointKind.VLLM_CHAT.deriveQueueName(body));
    }

    @Test
    void vllmChat_modelIdWithSlash_sanitizesIntoASinglePathSegment() {
        // Real HuggingFace-style ids look like "org/repo-name" — the "/" must not
        // survive into queueName, or /queue/{queueName} routing breaks.
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"google/gemma-4-26B-A4B-it\",\"object\":\"model\"}]}";

        String queueName = EndpointKind.VLLM_CHAT.deriveQueueName(body);

        assertEquals("vllm-google-gemma-4-26B-A4B-it", queueName);
        assertFalse(queueName.contains("/"), "queueName must be a single URL path segment");
    }

    @Test
    void vllmChat_malformedResponse_throws() {
        assertThrows(IllegalArgumentException.class, () -> EndpointKind.VLLM_CHAT.deriveQueueName("not json"));
    }

    @Test
    void vllmChat_missingDataField_throws() {
        assertThrows(IllegalArgumentException.class, () -> EndpointKind.VLLM_CHAT.deriveQueueName("{}"));
    }

    @Test
    void fixedFunctionKinds_deriveFixedQueueNames_regardlessOfProbeBody() {
        assertEquals("yomitoku-ocr", EndpointKind.YOMITOKU_OCR.deriveQueueName("anything"));
        assertEquals("marker-ocr", EndpointKind.MARKER_OCR.deriveQueueName("anything"));
        assertEquals("embedding-e5large", EndpointKind.EMBEDDING.deriveQueueName("anything"));
    }

    @Test
    void eachKind_hasItsOwnConventionalPortAndProbePath() {
        assertEquals(8000, EndpointKind.VLLM_CHAT.conventionalPort());
        assertEquals("/v1/models", EndpointKind.VLLM_CHAT.probePath());
        assertEquals(8013, EndpointKind.YOMITOKU_OCR.conventionalPort());
        assertEquals(8001, EndpointKind.MARKER_OCR.conventionalPort());
        assertEquals(8012, EndpointKind.EMBEDDING.conventionalPort());
    }

    @Test
    void createEndpoint_returnsTheMatchingSubclass() {
        assertInstanceOf(VllmChatEndpoint.class,
                EndpointKind.VLLM_CHAT.createEndpoint("q", "addr", NOOP_CLIENT));
        assertInstanceOf(YomiTokuOcrEndpoint.class,
                EndpointKind.YOMITOKU_OCR.createEndpoint("q", "addr", NOOP_CLIENT));
        assertInstanceOf(MarkerOcrEndpoint.class,
                EndpointKind.MARKER_OCR.createEndpoint("q", "addr", NOOP_CLIENT));
        assertInstanceOf(EmbeddingEndpoint.class,
                EndpointKind.EMBEDDING.createEndpoint("q", "addr", NOOP_CLIENT));
    }
}
