package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ChatQueueNameTest {

    @Test
    void modelIdBecomesAPathSafeQueueName() {
        assertEquals("chat-google-gemma-4-26B-A4B-it", ChatQueueName.of("google/gemma-4-26B-A4B-it"));
    }

    @Test
    void sanitizingIsIdempotent() {
        String sanitized = ChatQueueName.of("google/gemma-4-26B-A4B-it");
        assertEquals("chat-" + sanitized, ChatQueueName.of(sanitized));
    }

    @Test
    void aModelIdThatIsAlreadySafeIsKept() {
        String queueName = ChatQueueName.of("Qwen2.5-14B-Instruct-AWQ");
        assertEquals("chat-Qwen2.5-14B-Instruct-AWQ", queueName);
        assertFalse(queueName.substring("chat-".length()).contains("-14B-Instruct-AWQ-"));
    }

    @Test
    void theNameAClientBuiltBeforeTheRenameAsksForResolvesToTheSameQueue() {
        assertEquals("chat-qwen3.8-flash-next", ChatQueueName.resolve("vllm-qwen3.8-flash-next"));
        assertEquals("chat-qwen3.8-flash-next", ChatQueueName.resolve("chat-qwen3.8-flash-next"));
        assertEquals("marker-ocr", ChatQueueName.resolve("marker-ocr"));
        assertEquals(null, ChatQueueName.resolve(null));
    }
}
