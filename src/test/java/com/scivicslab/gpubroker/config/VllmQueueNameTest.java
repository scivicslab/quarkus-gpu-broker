package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class VllmQueueNameTest {

    @Test
    void of_sanitizesSlashesAndKeepsRestAsIs() {
        assertEquals("vllm-google-gemma-4-26B-A4B-it", VllmQueueName.of("google/gemma-4-26B-A4B-it"));
    }

    @Test
    void of_alreadySanitizedInput_isIdempotent() {
        // A model id containing no forbidden characters must be left untouched by a second pass.
        String sanitized = VllmQueueName.of("google/gemma-4-26B-A4B-it").substring("vllm-".length());
        assertEquals("vllm-" + sanitized, VllmQueueName.of(sanitized));
    }

    @Test
    void of_modelWithNoSpecialChars_isUnchangedApartFromPrefix() {
        String queueName = VllmQueueName.of("Qwen2.5-14B-Instruct-AWQ");
        assertEquals("vllm-Qwen2.5-14B-Instruct-AWQ", queueName);
        assertFalse(queueName.substring("vllm-".length()).contains("/"));
    }
}
