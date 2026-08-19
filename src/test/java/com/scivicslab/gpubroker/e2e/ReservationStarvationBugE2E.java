package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.restassured.RestAssured;

/**
 * Regression test for a {@code JobQueue.submit()} starvation bug: a worker
 * that goes idle while reserved (see {@code NodeReservation_260810_oo01})
 * used to be left holding a background job in the deque behind it forever
 * — {@code submit()}'s fast path checks only whether a worker is idle, not
 * whether the deque already has older jobs waiting, so newer jobs kept
 * jumping the queue. Fixed by giving {@code JobQueue} an {@code
 * ActorSystem} and a periodic {@code Scheduler} reconciliation (see {@code
 * 010_JobQueueReservationStarvationBug_260819_oo01} for the full
 * explanation and verification history — this test used to FAIL
 * reproducibly before that fix).
 *
 * <p>Deliberately NOT called from {@link GpuBrokerE2ERunner}: it still
 * takes about 3.5 minutes (waiting out two real reservation windows).
 * Run standalone:
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
        System.out.println("--- ReservationStarvationBugE2E --- (regression test for the reservation"
                + " starvation bug; takes ~3.5 minutes)");

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
        // without checking that the step-2 job is still waiting in the deque — and
        // because it is FOREGROUND, it re-reserves W for another 3 minutes.
        sendChatCompletion("foreground");
        LOG.info("Step 4 done: a new foreground job was submitted and served (re-reserves W for "
                + RESERVATION_WINDOW.toSeconds() + "s more)");

        // Immediate check: the old symptom — the step-2 job is not resolved just because a
        // new job arrived and was served.
        TimeUnit.SECONDS.sleep(3);
        boolean stillStuckRightAfterStep4 = !stuckJob.isDone();
        LOG.info("Immediately after step 4: step-2 job still stuck=" + stillStuckRightAfterStep4);

        // Wait out the reservation step 4 itself just created, plus a reconciliation margin,
        // for JobQueue's periodic reconciliation (every 30s, see
        // JobQueueReservationStarvationBug_260819_oo01) to have a real chance to pick the
        // step-2 job up once nothing is re-reserving W anymore.
        Duration reconcilePollWindow = RESERVATION_WINDOW.plusSeconds(45);
        long deadline = System.nanoTime() + reconcilePollWindow.toNanos();
        while (System.nanoTime() < deadline && !stuckJob.isDone()) {
            TimeUnit.SECONDS.sleep(5);
        }

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
