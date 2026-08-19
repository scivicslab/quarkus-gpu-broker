package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.restassured.RestAssured;

/**
 * Priority ordering (scenario 4 of {@code E2ETestPlan_260810_oo01}): a
 * foreground job queued after a background job completes before it.
 *
 * <p>Needs a queue with exactly one real {@code AiServiceEndpoint} so
 * dispatch order is observable — reuses {@code vllm-Qwen2.5-14B-Instruct-AWQ}
 * (192.168.5.14:8000, {@code max-concurrency=1}, see
 * {@code CappedConcurrencyLoadE2E}) rather than a stub, since the real
 * cluster already has exactly this shape.
 *
 * <p>Measured directly against this node: a short prompt like "count to
 * five" hits its own stop token well under {@code max_tokens}, completing
 * in ~0.3s regardless of {@code max_tokens} — raising {@code max_tokens}
 * alone does not make a job run longer. Forcing job1 to actually emit
 * ~1000 tokens (a long-essay prompt) reliably takes ~15s on this node,
 * giving comfortable headroom for job2/job3, fired 100ms apart, to land as
 * {@code pending} behind it without any polling.
 *
 * <p>job3 (foreground) being dispatched itself re-reserves the worker for
 * another 3 minutes (see {@code NodeReservation_260810_oo01}) — the same
 * worker job2 (background) is waiting behind. So job2's own completion is
 * genuinely delayed by up to that full reservation window; only job1 and
 * job3 are awaited on the short timeout, job2 gets a much longer one.
 * See {@code JobQueueReservationStarvationBug_260819_oo01} for why this
 * is not itself a bug: it eventually resolves via periodic reconciliation,
 * not instantly.
 */
class PriorityOrderingE2E extends GpuBrokerE2EBase {

    private static final String QUEUE = "vllm-Qwen2.5-14B-Instruct-AWQ";

    public static void main(String[] args) throws Exception {
        new PriorityOrderingE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- PriorityOrderingE2E ---");

        // Job 1 (background): a long-essay prompt that actually emits ~1000
        // tokens (~15s on this node) — occupies the only worker long enough
        // for job2/job3 to reliably land as pending behind it.
        CompletableFuture<Void> job1 = CompletableFuture.runAsync(() -> sendLongJob("background"));
        TimeUnit.MILLISECONDS.sleep(100);

        // Job 2 (background): queued first.
        AtomicLong job2CompletedAt = new AtomicLong();
        CompletableFuture<Void> job2 = CompletableFuture.runAsync(() -> {
            sendShortJob("background");
            job2CompletedAt.set(System.nanoTime());
        });
        TimeUnit.MILLISECONDS.sleep(100);

        // Job 3 (foreground): queued second, but must dispatch before job2.
        AtomicLong job3CompletedAt = new AtomicLong();
        CompletableFuture<Void> job3 = CompletableFuture.runAsync(() -> {
            sendShortJob("foreground");
            job3CompletedAt.set(System.nanoTime());
        });

        // 60s, not 30s: real inference latency for job1's ~1000-token essay varies with
        // cluster load, and job3 cannot even start until job1 finishes.
        CompletableFuture.allOf(job1, job3).get(60, TimeUnit.SECONDS);
        // job2 is delayed by job3's own re-reservation of the worker (see class Javadoc) —
        // give it up to the full reservation window plus a reconciliation margin.
        job2.get(Duration.ofMinutes(3).plusSeconds(45).toSeconds(), TimeUnit.SECONDS);

        require(job3CompletedAt.get() < job2CompletedAt.get(),
                "foreground job (job3) must complete before the earlier-queued background job (job2)");

        LOG.info("job3 (foreground) completed before job2 (background), as required");
        System.out.println("PriorityOrderingE2E: PASSED");
    }

    private void sendLongJob(String priority) {
        int status = RestAssured.given().baseUri(BASE_URL)
                .header("X-Job-Priority", priority)
                .contentType("application/json")
                .body("""
                        {"model":"Qwen2.5-14B-Instruct-AWQ","max_tokens":1000,
                         "messages":[{"role":"user","content":
                           "Write a very long, detailed essay of at least 900 words about the history of computing."}]}
                        """)
                .when().post("/queue/" + QUEUE)
                .then().extract().statusCode();
        require(status == 200, "expected 200 from " + QUEUE + ", got " + status);
    }

    private void sendShortJob(String priority) {
        int status = RestAssured.given().baseUri(BASE_URL)
                .header("X-Job-Priority", priority)
                .contentType("application/json")
                .body("""
                        {"model":"Qwen2.5-14B-Instruct-AWQ","max_tokens":20,
                         "messages":[{"role":"user","content":"count to five"}]}
                        """)
                .when().post("/queue/" + QUEUE)
                .then().extract().statusCode();
        require(status == 200, "expected 200 from " + QUEUE + ", got " + status);
    }
}
