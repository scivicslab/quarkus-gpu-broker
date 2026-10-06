package com.scivicslab.gpubroker.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** What the registry promises for a queue whose endpoints do not agree, and for one that said nothing. */
class ContextLengthPerQueueTest {

    @Test
    void aQueueCanOnlyPromiseTheSmallestWindowItsEndpointsAccept() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.putContextLength("chat-qwen3.8-flash-next-iq3_xxs", 262144);
        state.putContextLength("chat-qwen3.8-flash-next-iq3_xxs", 131072);
        assertEquals(Map.of("chat-qwen3.8-flash-next-iq3_xxs", 131072), state.contextLengths());
    }

    @Test
    void anUnknownWindowIsAbsentRatherThanZero() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.putContextLength("chat-mystery", 0);
        assertFalse(state.contextLengths().containsKey("chat-mystery"));
    }

    @Test
    void removingAQueueForgetsItsWindow() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.registerQueue("chat-gone", () -> null);
        state.putContextLength("chat-gone", 131072);
        state.removeQueue("chat-gone");
        assertFalse(state.contextLengths().containsKey("chat-gone"));
    }
}
