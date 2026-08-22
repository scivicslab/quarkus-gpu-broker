package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EndpointProbe implementations — per-kind queueName derivation and protocol facts")
class EndpointProbeTest {

    private final VllmChatProbe vllmChat = new VllmChatProbe();
    private final YomiTokuOcrProbe yomiTokuOcr = new YomiTokuOcrProbe();
    private final MarkerOcrProbe markerOcr = new MarkerOcrProbe();
    private final EmbeddingProbe embedding = new EmbeddingProbe();
    private final WhisperTranscriptProbe whisperTranscript = new WhisperTranscriptProbe();

    @Test
    void vllmChat_derivesQueueNameFromModelsResponse() {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4\",\"object\":\"model\"}]}";

        assertEquals("vllm-gemma-4", vllmChat.deriveQueueName(body).orElseThrow());
    }

    @Test
    void vllmChat_modelIdWithSlash_sanitizesIntoASinglePathSegment() {
        // Real HuggingFace-style ids look like "org/repo-name" — the "/" must not
        // survive into queueName, or /queue/{queueName} routing breaks.
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"google/gemma-4-26B-A4B-it\",\"object\":\"model\"}]}";

        String queueName = vllmChat.deriveQueueName(body).orElseThrow();

        assertEquals("vllm-google-gemma-4-26B-A4B-it", queueName);
        assertFalse(queueName.contains("/"), "queueName must be a single URL path segment");
    }

    @Test
    void vllmChat_malformedResponse_isEmpty() {
        assertTrue(vllmChat.deriveQueueName("not json").isEmpty());
    }

    @Test
    void vllmChat_missingDataField_isEmpty() {
        assertTrue(vllmChat.deriveQueueName("{}").isEmpty());
    }

    @Test
    void fixedFunctionKinds_deriveFixedQueueNames_regardlessOfProbeBody() {
        assertEquals("yomitoku-ocr", yomiTokuOcr.deriveQueueName("anything").orElseThrow());
        assertEquals("marker-ocr", markerOcr.deriveQueueName("anything").orElseThrow());
        assertEquals("embedding-e5large", embedding.deriveQueueName("anything").orElseThrow());
        assertEquals("whisper-transcript", whisperTranscript.deriveQueueName("anything").orElseThrow());
    }

    @Test
    void eachKind_hasItsOwnConventionalPortAndProbePath() {
        assertEquals(8000, vllmChat.conventionalPort());
        assertEquals("/v1/models", vllmChat.probePath());
        assertEquals(8013, yomiTokuOcr.conventionalPort());
        // Real YomiToku/Marker/embedding deployments report status at "/", not "/health" —
        // confirmed by probing real nodes; "/health" 404s on all three.
        assertEquals("/", yomiTokuOcr.probePath());
        assertEquals(8001, markerOcr.conventionalPort());
        assertEquals("/", markerOcr.probePath());
        assertEquals(8012, embedding.conventionalPort());
        assertEquals("/", embedding.probePath());
        assertEquals(8003, whisperTranscript.conventionalPort());
        // Unlike YomiToku/Marker/embedding, "/" 404s on this FastAPI app and "/health" answers instead.
        assertEquals("/health", whisperTranscript.probePath());
    }

    @Test
    void eachKind_hasItsOwnRequestPath() {
        assertEquals("/v1/chat/completions", vllmChat.requestPath());
        assertEquals("/ocr", yomiTokuOcr.requestPath());
        assertEquals("/marker/upload", markerOcr.requestPath());
        assertEquals("/v1/embeddings", embedding.requestPath());
        assertEquals("/transcript", whisperTranscript.requestPath());
    }

    @Test
    void eachKind_hasADefaultMaxConcurrency() {
        assertEquals(32, vllmChat.defaultMaxConcurrency());
        assertEquals(1, yomiTokuOcr.defaultMaxConcurrency());
        assertEquals(1, markerOcr.defaultMaxConcurrency());
        assertEquals(8, embedding.defaultMaxConcurrency());
        assertEquals(1, whisperTranscript.defaultMaxConcurrency());
    }
}
