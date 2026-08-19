package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.restassured.RestAssured;

/**
 * Node reservation after a foreground job (scenario 7 of
 * {@code E2ETestPlan_260810_oo01}, {@code NodeReservation_260810_oo01}):
 * a background job submitted right after a foreground job finishes must
 * NOT dispatch for ~3 minutes.
 *
 * <p>Reuses {@code vllm-Qwen2.5-14B-Instruct-AWQ} (single real endpoint,
 * see {@link PriorityOrderingE2E}) so the reservation is observable via
 * the status page's active count. Takes about 3 minutes to run — this is
 * the slowest scenario in the suite, but it needs no stub or broker
 * restart, so it stays in the E2E runner rather than becoming a {@code
 * *IT.java}.
 */
class ForegroundReservationE2E extends GpuBrokerE2EBase {

    private static final String QUEUE = "vllm-Qwen2.5-14B-Instruct-AWQ";
    private static final Duration RESERVATION_WINDOW = Duration.ofMinutes(3);

    public static void main(String[] args) throws Exception {
        new ForegroundReservationE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- ForegroundReservationE2E --- (takes ~3 minutes)");

        sendChatCompletion("foreground");

        CompletableFuture<Void> bgJob = CompletableFuture.runAsync(() -> sendChatCompletion("background"));
        long submittedAt = System.nanoTime();

        // Must NOT dispatch while the reservation is held (checked partway through the window).
        TimeUnit.SECONDS.sleep(RESERVATION_WINDOW.toSeconds() - 30);
        require(readActiveCount(QUEUE) == 0,
                "background job must not dispatch within the reservation window; active="
                        + readActiveCount(QUEUE));
        LOG.info("Confirmed still reserved at " + elapsedSeconds(submittedAt) + "s");

        // Must dispatch once the reservation lapses (checked via active count OR the job
        // completing outright — a fast dispatch-then-finish can happen between polls).
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean dispatched = false;
        while (System.nanoTime() < deadline) {
            if (readActiveCount(QUEUE) > 0 || bgJob.isDone()) {
                dispatched = true;
                break;
            }
            TimeUnit.SECONDS.sleep(5);
        }
        long dispatchedAtSeconds = elapsedSeconds(submittedAt);
        require(dispatched, "background job never dispatched within " + dispatchedAtSeconds
                + "s of the reservation window");

        bgJob.get(60, TimeUnit.SECONDS);
        LOG.info("Background job dispatched at " + dispatchedAtSeconds + "s (reservation window="
                + RESERVATION_WINDOW.toSeconds() + "s)");
        System.out.println("ForegroundReservationE2E: PASSED");
    }

    private static long elapsedSeconds(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toSeconds();
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
