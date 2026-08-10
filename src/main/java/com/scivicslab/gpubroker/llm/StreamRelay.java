package com.scivicslab.gpubroker.llm;

import java.util.Iterator;

import com.scivicslab.gpubroker.model.Job;

/**
 * Relays upstream response lines into a job's sink and detects completion.
 *
 * <p>This is the last link of the completion-driven loop: each upstream line is
 * forwarded to the client sink, and the job is completed when the stream ends —
 * either at the {@code [DONE]} sentinel or when the upstream lines are exhausted
 * (non-stream response). Completing the job releases the waiting request thread
 * and lets the {@code NodeActor} pull the next job.
 *
 * <p>The same {@code pump} drives both the real HTTP client and the test stub
 * upstream, so the completion-detection logic is exercised without a network.
 */
public final class StreamRelay {

    private StreamRelay() {
    }

    /** Forward every line to the job's sink, completing the job at end of stream. */
    public static void pump(Iterator<String> lines, Job job) {
        while (lines.hasNext()) {
            String line = lines.next();
            job.sink().write(line);
            if (isDone(line)) {
                job.complete();          // completed exactly at [DONE]
            }
        }
        job.complete();                  // non-stream / no sentinel → complete at end (idempotent)
    }

    /** Whether a line is the OpenAI streaming end sentinel. */
    public static boolean isDone(String line) {
        String s = line.strip();
        return s.equals("[DONE]") || s.equals("data: [DONE]") || s.endsWith("data: [DONE]");
    }
}
