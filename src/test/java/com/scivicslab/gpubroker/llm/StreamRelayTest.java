package com.scivicslab.gpubroker.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.model.Job;

/**
 * S_proxy (unit): the relay forwards every upstream chunk to the client sink in
 * order and completes the job exactly when the {@code [DONE]} sentinel passes.
 */
@Tag("S_proxy")
@DisplayName("StreamRelay — relays chunks and completes on [DONE] (S_proxy)")
class StreamRelayTest {

    @Test
    void relaysChunksAndCompletesOnDone() {
        StubStreamUpstream upstream = new StubStreamUpstream("chunk-1", "chunk-2", "[DONE]");
        RecordingSink sink = new RecordingSink();
        Job job = Job.bgStreaming(sink);

        upstream.send("http://stub", job);   // relays to sink, completes job on [DONE]

        assertEquals(List.of("chunk-1", "chunk-2", "[DONE]"), sink.received());
        assertTrue(job.isCompleted());        // completed at [DONE]
    }
}
