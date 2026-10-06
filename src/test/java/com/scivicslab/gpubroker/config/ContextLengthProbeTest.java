package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * The three servers behind the one chat probe each report the window somewhere else, and a client
 * has to be told the number before it sends a long document.
 */
class ContextLengthProbeTest {

    private final VllmChatProbe probe = new VllmChatProbe();

    @Test
    void vllmReportsItAsMaxModelLen() {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3.8-flash-next\",\"max_model_len\":262144}]}";
        assertEquals(OptionalInt.of(262144), probe.deriveContextLength(body));
    }

    @Test
    void strataReportsItAsMetaNCtx() {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3.8-flash-next-iq3_xxs\","
                + "\"meta\":{\"n_ctx\":131072}}]}";
        assertEquals(OptionalInt.of(131072), probe.deriveContextLength(body));
    }

    @Test
    void tensorfoldReportsNothingAndIsLeftToTheDeclaredValue() {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3.8-flash-next-tensorfold\","
                + "\"object\":\"model\",\"owned_by\":\"tensorfold\"}]}";
        assertFalse(probe.deriveContextLength(body).isPresent());
    }

    @Test
    void anAnswerThatIsNotJsonIsNotAWindow() {
        assertFalse(probe.deriveContextLength("<html>502 Bad Gateway</html>").isPresent());
    }
}
