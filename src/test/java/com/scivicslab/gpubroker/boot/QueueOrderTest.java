package com.scivicslab.gpubroker.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The registry hands out its queues ordered by name. Before this, the maps were {@code HashMap}s
 * copied with {@code Map.copyOf}, whose iteration order is salted per JVM: {@code GET /queues},
 * the status page and {@code GET /v1/models} listed the same queues in a different order after
 * every restart, and a client that takes "the first model" got a different one each time.
 */
class QueueOrderTest {

    @Test
    void queuesAndDisplayNamesComeBackSortedByQueueName() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        List<String> inserted = List.of("yomitoku-ocr", "vllm-qwen3.8-flash-next", "embedding-e5large",
                "marker-ocr", "vllm-google-gemma-4-26B-A4B-it", "whisper-transcript");
        for (String name : inserted) {
            state.registerQueue(name, () -> null);
            state.putDisplayName(name, name + " display");
        }

        List<String> expected = inserted.stream().sorted().toList();
        assertEquals(expected, List.copyOf(state.queueMap().keySet()));
        assertEquals(expected, List.copyOf(state.displayNames().keySet()));
    }
}
