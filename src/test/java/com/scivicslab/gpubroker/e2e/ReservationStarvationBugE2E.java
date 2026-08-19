package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.restassured.RestAssured;

/**
 * Reproduces a known, unfixed bug in {@code JobQueue.submit()}: a worker
 * that goes idle while reserved (see {@code NodeReservation_260810_oo01})
 * is left holding a background job in the deque behind it. Once the
 * reservation lapses, nothing re-checks that worker on its own — the next
 * new job to arrive gets handed the worker directly ({@code
 * JobQueue.submit()}'s fast path checks only whether a worker is idle, not
 * whether the deque already has older jobs waiting), so the old
 * background job stays stuck. See
 * {@code 010_JobQueueReservationStarvationBug_260819_oo01} for the full
 * explanation.
 *
 * <p>This test currently FAILS — that is the point. It should start
 * passing once the bug is fixed (either the periodic {@code Scheduler}
 * mitigation discussed in that document, or the deeper fix to {@code
 * JobQueue.submit()} itself).
 *
 * <p>Deliberately NOT called from {@link GpuBrokerE2ERunner}: it takes
 * over 3 minutes, and — because the bug it reproduces is real — it
 * LEAVES the queue with a permanently stuck job. Restart
 * quarkus-gpu-broker after running this to clear it. Run standalone:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.gpubroker.e2e.ReservationStarvationBugE2E \
 *     -Dexec.classpathScope=test \
 *     -De2e.base.url=http://localhost:28003
 * </pre>
 */
class ReservationStarvationBugE2E extends GpuBrokerE2EBase {

    private static final String QUEUE = "vllm-Qwen2.5-14B-Instruct-AWQ";
    private static final Duration RESERVATION_WINDOW = Duration.ofMinutes(3);

    public static void main(String[] args) throws Exception {
        new ReservationStarvationBugE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- ReservationStarvationBugE2E --- (reproduces a known bug; takes ~3.5 minutes;"
                + " LEAVES the queue with a stuck job — restart quarkus-gpu-broker afterward)");

        // Step 1: a foreground job reserves the (only) worker for 3 minutes.
        sendChatCompletion("foreground");
        LOG.info("Step 1 done: worker reserved for " + RESERVATION_WINDOW.toSeconds() + "s");

        // Step 2: a background job arrives while reserved -> parked in the deque.
        CompletableFuture<Void> stuckJob = CompletableFuture.runAsync(() -> sendChatCompletion("background"));
        TimeUnit.SECONDS.sleep(1); // let it actually reach the deque before checking
        int pendingAfterQueueing = readPendingCount(QUEUE);
        require(pendingAfterQueueing >= 1,
                "expected the background job to be queued (pending>=1), got pending=" + pendingAfterQueueing);
        LOG.info("Step 2 done: background job queued, pending=" + pendingAfterQueueing);

        // Step 3: wait past the reservation window.
        TimeUnit.SECONDS.sleep(RESERVATION_WINDOW.toSeconds() + 15);
        LOG.info("Step 3 done: reservation window has lapsed");

        // Step 4: a brand-new foreground job arrives after the reservation lapses.
        // JobQueue.submit()'s fast path hands it the now-idle worker directly,
        // without checking that the step-2 job is still waiting in the deque.
        sendChatCompletion("foreground");
        LOG.info("Step 4 done: a new foreground job was submitted and served");

        // Give the queue a moment to settle, then check whether the step-2 job is still stuck.
        TimeUnit.SECONDS.sleep(3);

        require(stuckJob.isDone(),
                "the background job from step 2 must eventually be dispatched, not permanently stuck — "
                        + "but it is still waiting (pending=" + readPendingCount(QUEUE) + "). This is the "
                        + "JobQueue.submit() starvation bug: submit() hands a freshly-idle worker directly "
                        + "to whichever job arrives next, without checking whether older jobs are already "
                        + "waiting in the deque.");

        System.out.println("ReservationStarvationBugE2E: PASSED (bug appears fixed)");
    }

    private void sendChatCompletion(String priority) {
        int status = RestAssured.given().baseUri(BASE_URL)
                .header("X-Job-Priority", priority)
                .contentType("application/json")
                .body("""
                        {"model":"Qwen2.5-14B-Instruct-AWQ","max_tokens":10,
                         "messages":[{"role":"user","content":"say hi"}]}
                        """)
                .when().post("/queue/" + QUEUE)
                .then().extract().statusCode();
        require(status == 200, "expected 200 from " + QUEUE + ", got " + status);
    }
}
