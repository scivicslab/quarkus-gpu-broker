package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.restassured.RestAssured;

/**
 * Submit order preserved among same-priority jobs (the deeper half of the
 * fix in {@code JobQueueReservationStarvationBug_260819_oo01}): once a
 * worker frees up, an older, already-queued job must be dispatched before
 * a job that happens to be submitted right at that moment — not the other
 * way around.
 *
 * <p>Distinct from {@link ForegroundReservationE2E} (which shows the
 * reservation itself holds) and {@link PriorityOrderingE2E} (foreground
 * jumping background, which is intended). Both jobs here are BACKGROUND —
 * there is no priority difference to explain job A finishing first, only
 * arrival order. Before the {@code JobQueue.submit()} fix, {@code
 * submit()} handed the newly-idle worker directly to whichever job
 * arrived next, so job B (submitted after the reservation lapsed) could
 * jump ahead of job A (already queued since before it lapsed) purely by
 * chance of timing.
 */
class SubmitOrderPreservedE2E extends GpuBrokerE2EBase {

    private static final String QUEUE = "vllm-Qwen2.5-14B-Instruct-AWQ";
    private static final Duration RESERVATION_WINDOW = Duration.ofMinutes(3);

    public static void main(String[] args) throws Exception {
        new SubmitOrderPreservedE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- SubmitOrderPreservedE2E --- (takes ~3.5 minutes)");

        // Reserve the (only) worker with a foreground job, exactly as the starvation bug's
        // reproduction does.
        sendChatCompletion("foreground");

        // Job A (background): arrives while reserved -> queued.
        AtomicLong aCompletedAt = new AtomicLong();
        CompletableFuture<Void> jobA = CompletableFuture.runAsync(() -> {
            sendChatCompletion("background");
            aCompletedAt.set(System.nanoTime());
        });
        TimeUnit.SECONDS.sleep(1);   // let it actually reach the deque before waiting out the reservation

        // Wait out the reservation window (plus margin) so the worker becomes eligible again.
        TimeUnit.SECONDS.sleep(RESERVATION_WINDOW.toSeconds() + 15);

        // Job B (background): submitted right as the worker becomes eligible. Must NOT be
        // the one dispatched — job A was already waiting.
        AtomicLong bCompletedAt = new AtomicLong();
        CompletableFuture<Void> jobB = CompletableFuture.runAsync(() -> {
            sendChatCompletion("background");
            bCompletedAt.set(System.nanoTime());
        });

        CompletableFuture.allOf(jobA, jobB).get(60, TimeUnit.SECONDS);

        require(aCompletedAt.get() < bCompletedAt.get(),
                "job A (queued first, while the worker was reserved) must complete before "
                        + "job B (submitted only after the reservation lapsed) — submit() must not "
                        + "hand the newly-idle worker to whichever job happens to arrive next");

        LOG.info("job A completed before job B, as required");
        System.out.println("SubmitOrderPreservedE2E: PASSED");
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
