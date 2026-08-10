package com.scivicslab.gpubroker.response;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JobResultStore — register/complete/fail lifecycle and TTL sweep")
class JobResultStoreTest {

    @Test
    void register_startsPending() {
        JobResultStore store = new JobResultStore();

        String jobId = store.register();

        assertEquals(JobResult.Status.PENDING, store.get(jobId).orElseThrow().status());
    }

    @Test
    void complete_carriesBodyAndContentType_keepsOriginalCreatedAt() {
        JobResultStore store = new JobResultStore();
        String jobId = store.register();
        JobResult pending = store.get(jobId).orElseThrow();

        store.complete(jobId, "result".getBytes(StandardCharsets.UTF_8), "application/json");

        JobResult done = store.get(jobId).orElseThrow();
        assertEquals(JobResult.Status.DONE, done.status());
        assertArrayEquals("result".getBytes(StandardCharsets.UTF_8), done.body());
        assertEquals("application/json", done.contentType());
        assertEquals(pending.createdAt(), done.createdAt());
    }

    @Test
    void fail_recordsErrorMessage() {
        JobResultStore store = new JobResultStore();
        String jobId = store.register();

        store.fail(jobId, new RuntimeException("boom"));

        JobResult failed = store.get(jobId).orElseThrow();
        assertEquals(JobResult.Status.FAILED, failed.status());
        assertEquals("boom", failed.error());
    }

    @Test
    void get_unknownJobId_isEmpty() {
        JobResultStore store = new JobResultStore();

        assertTrue(store.get("no-such-job").isEmpty());
    }

    @Test
    void sweep_removesEntriesOlderThanTtl_keepsFresherOnes() throws InterruptedException {
        JobResultStore store = new JobResultStore(Duration.ofMillis(30));
        String oldJobId = store.register();

        Thread.sleep(50);   // outlive the 30ms TTL
        String freshJobId = store.register();

        store.sweep();

        assertTrue(store.get(oldJobId).isEmpty());
        assertFalse(store.get(freshJobId).isEmpty());
    }
}
