package com.scivicslab.gpubroker.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.scivicslab.gpubroker.model.GenerationMeasurement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationInTheHistoryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T14:23:00Z");
    private static final Instant WINDOW = Instant.parse("2026-09-15T14:20:00Z");

    @Test
    void recordGeneration_foldsIntoTheQueueAndTheAddress(@TempDir Path dir) {
        StatusHistoryStore store = new StatusHistoryStore(dir.resolve("history.jsonl"));

        store.recordGeneration(NOW, new GenerationMeasurement("vllm-a", "10.0.0.1:8000", 1500, 200, 4000, 400));
        store.recordGeneration(NOW, new GenerationMeasurement("vllm-a", "10.0.0.2:8000", 500, 100, 2000, 200));

        GenerationTotals queue = last(store.queueHistory("vllm-a")).generated();
        assertEquals(2, queue.generations());
        assertEquals(600, queue.tokens());
        assertEquals(1000, queue.meanQueuedMs(), "the wait no vLLM server can report");
        assertEquals(100.0, queue.tokensPerSecondPerReply(), 1e-9);

        assertEquals(400, last(store.endpointHistory("10.0.0.1:8000")).generated().tokens());
        assertEquals(200, last(store.endpointHistory("10.0.0.2:8000")).generated().tokens());
    }

    @Test
    void recordGeneration_forAJobThatNeverReachedAServer_countsOnTheQueueOnly(@TempDir Path dir) {
        StatusHistoryStore store = new StatusHistoryStore(dir.resolve("history.jsonl"));

        store.recordGeneration(NOW, new GenerationMeasurement("vllm-a", null, 900, 0, 0, 0));

        assertEquals(1, last(store.queueHistory("vllm-a")).generated().generations());
        assertTrue(store.addressesOf("vllm-a").isEmpty());
    }

    @Test
    void aHistoryFileWrittenBeforeTheseExisted_readsBackAsZeroes(@TempDir Path dir) throws IOException {
        // Exactly the shape the broker wrote until generations were measured: no such fields.
        Path file = dir.resolve("history.jsonl");
        Files.write(file, List.of(
                "{\"kind\":\"queue\",\"bucket\":\"" + WINDOW + "\",\"queue\":\"vllm-a\",\"samples\":3,"
                        + "\"activeSum\":6,\"idleSum\":9,\"pendingSum\":0,\"completed\":4,\"failed\":0}",
                "{\"kind\":\"endpoint\",\"bucket\":\"" + WINDOW + "\",\"address\":\"10.0.0.1:8000\","
                        + "\"queue\":\"vllm-a\",\"probeOk\":3,\"probeTotal\":3}"),
                StandardCharsets.UTF_8);

        StatusHistoryStore store = new StatusHistoryStore(file);
        store.load(NOW);

        QueueBucket restored = last(store.queueHistory("vllm-a"));
        assertEquals(4, restored.completed(), "what the old file did hold is still read");
        assertEquals(GenerationTotals.NONE, restored.generated());
        assertEquals(GenerationTotals.NONE, last(store.endpointHistory("10.0.0.1:8000")).generated());
    }

    @Test
    void whatWasMeasured_survivesBeingWrittenAndReadBack(@TempDir Path dir) {
        Path file = dir.resolve("history.jsonl");
        StatusHistoryStore writing = new StatusHistoryStore(file);
        writing.recordGeneration(NOW, new GenerationMeasurement("vllm-a", "10.0.0.1:8000", 1500, 200, 4000, 400));
        writing.flush();

        StatusHistoryStore reading = new StatusHistoryStore(file);
        reading.load(NOW.plusSeconds(60));

        GenerationTotals totals = last(reading.queueHistory("vllm-a")).generated();
        assertEquals(1, totals.generations());
        assertEquals(400, totals.tokens());
        assertEquals(4000, totals.decodeMs());
        assertEquals(1500, totals.queuedMs());
        assertEquals(200, totals.firstMs());
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }
}
