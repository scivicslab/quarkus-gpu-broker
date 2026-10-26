package com.scivicslab.gpubroker.response;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * A broker restart is a new {@link JobResultStore} over the same directory. What a client asked
 * before the restart must still be answerable after it: a finished job with its result, an
 * unfinished one as a job to run again -- never as an unknown id (the client treats that as "not
 * yet" and waits for ever).
 */
@Tag("JobsSurviveRestart_261025_oo01")
@DisplayName("JobResultStore — jobs survive a restart")
class JobResultStoreRestartTest {

    private static RequestBody request(String text) {
        return new RequestBody(text.getBytes(StandardCharsets.UTF_8), "application/pdf");
    }

    @Test
    @DisplayName("a finished job answers GET after a restart, with the same body and content type")
    void finishedJob_answersAfterRestart(@TempDir Path dir) {
        JobResultStore before = new JobResultStore(new JobJournal(dir));
        String jobId = before.register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 1"));
        before.complete(jobId, "# result".getBytes(StandardCharsets.UTF_8), "application/json");

        JobResultStore after = new JobResultStore(new JobJournal(dir));

        JobResult result = after.get(jobId).orElseThrow();
        assertEquals(JobResult.Status.DONE, result.status());
        assertArrayEquals("# result".getBytes(StandardCharsets.UTF_8), result.body());
        assertEquals("application/json", result.contentType());
        assertTrue(after.recoverable().isEmpty(), "a finished job is not run again");
    }

    @Test
    @DisplayName("a failed job stays failed, with its message")
    void failedJob_staysFailed(@TempDir Path dir) {
        JobResultStore before = new JobResultStore(new JobJournal(dir));
        String jobId = before.register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 1"));
        before.fail(jobId, new RuntimeException("boom"));

        JobResult result = new JobResultStore(new JobJournal(dir)).get(jobId).orElseThrow();

        assertEquals(JobResult.Status.FAILED, result.status());
        assertEquals("boom", result.error());
    }

    @Test
    @DisplayName("an unfinished job is PENDING after a restart and listed to run again, with everything needed to run it")
    void unfinishedJob_isRecoverable(@TempDir Path dir) {
        JobResultStore before = new JobResultStore(new JobJournal(dir));
        String jobId = before.register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 7"));

        JobResultStore after = new JobResultStore(new JobJournal(dir));

        assertEquals(JobResult.Status.PENDING, after.get(jobId).orElseThrow().status(),
                "the id is known, so the client keeps waiting for a real answer");
        List<JobJournal.Accepted> again = after.recoverable();
        assertEquals(1, again.size());
        assertEquals("marker-ocr", again.get(0).queueName());
        assertEquals(Priority.BACKGROUND, again.get(0).priority());
        assertEquals("html-saurus", again.get(0).submitterId());
        assertEquals("application/pdf", again.get(0).contentType());
        assertArrayEquals("page 7".getBytes(StandardCharsets.UTF_8), again.get(0).body());
    }

    @Test
    @DisplayName("a job put back on its queue is no longer listed, and its result survives the next restart")
    void resubmittedJob_endsAndSurvives(@TempDir Path dir) {
        String jobId = new JobResultStore(new JobJournal(dir))
                .register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 7"));
        JobResultStore second = new JobResultStore(new JobJournal(dir));

        second.resubmitted(jobId);
        assertTrue(second.recoverable().isEmpty());
        second.complete(jobId, "ok".getBytes(StandardCharsets.UTF_8), "text/plain");

        JobResultStore third = new JobResultStore(new JobJournal(dir));
        assertEquals(JobResult.Status.DONE, third.get(jobId).orElseThrow().status());
        assertTrue(third.recoverable().isEmpty());
    }

    @Test
    @DisplayName("a job that was resubmitted but not finished before the next restart is run again")
    void resubmittedButUnfinished_isRecoverableAgain(@TempDir Path dir) {
        String jobId = new JobResultStore(new JobJournal(dir))
                .register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 7"));
        JobResultStore second = new JobResultStore(new JobJournal(dir));
        second.resubmitted(jobId);

        JobResultStore third = new JobResultStore(new JobJournal(dir));

        assertEquals(1, third.recoverable().size());
    }

    @Test
    @DisplayName("sweep deletes the files of expired jobs, and a restart does not bring them back")
    void sweep_deletesFiles(@TempDir Path dir) throws Exception {
        JobResultStore store = new JobResultStore(Duration.ofMillis(30), new JobJournal(dir));
        String jobId = store.register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 1"));
        store.complete(jobId, "x".getBytes(StandardCharsets.UTF_8), "text/plain");
        Thread.sleep(60);

        store.sweep();

        assertTrue(store.get(jobId).isEmpty());
        assertTrue(new JobResultStore(Duration.ofMillis(30), new JobJournal(dir)).get(jobId).isEmpty());
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count(), "no file of the expired job is left");
        }
    }

    @Test
    @DisplayName("jobs older than the TTL are dropped when read back")
    void restore_dropsExpired(@TempDir Path dir) throws Exception {
        JobResultStore before = new JobResultStore(Duration.ofMillis(30), new JobJournal(dir));
        String jobId = before.register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 1"));
        Thread.sleep(60);

        JobResultStore after = new JobResultStore(Duration.ofMillis(30), new JobJournal(dir));

        assertTrue(after.get(jobId).isEmpty());
        assertTrue(after.recoverable().isEmpty());
    }

    @Test
    @DisplayName("a damaged file is skipped; the other jobs are still read")
    void damagedFile_isSkipped(@TempDir Path dir) throws IOException {
        JobResultStore before = new JobResultStore(new JobJournal(dir));
        String good = before.register("marker-ocr", Priority.BACKGROUND, "html-saurus", request("page 1"));
        Files.write(dir.resolve("broken.req"), new byte[] {1, 2, 3});

        JobResultStore after = new JobResultStore(new JobJournal(dir));

        assertEquals(1, after.recoverable().size());
        assertEquals(good, after.recoverable().get(0).jobId());
    }

    @Test
    @DisplayName("a half-written temporary file left by a crash is removed, not read")
    void temporaryFile_isRemoved(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("x.res.tmp"), new byte[] {1});

        new JobResultStore(new JobJournal(dir));

        assertTrue(Files.notExists(dir.resolve("x.res.tmp")));
    }
}
