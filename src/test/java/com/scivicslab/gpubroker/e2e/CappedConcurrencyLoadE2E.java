package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.restassured.RestAssured;

/**
 * Simple load test: fires concurrent requests at 192.168.5.14:8000 (a shared,
 * non-dedicated GPU node) and confirms the already-running broker's
 * {@code max-concurrency=1} override for it — configured via AI-workspace's
 * {@code config/application.yaml}, see {@code CapabilityConfig_260810_oo01}
 * — actually holds, not just that it is declared.
 *
 * <p>Each request asks for a ~900-word essay, which takes ~15s on this node
 * (measured, see {@code PriorityOrderingE2E}). A five-token reply finishes in
 * ~0.3s, so polling {@code /queues} every 100ms missed every one of them and
 * the run recorded a maximum of zero jobs active — which satisfies "no more
 * than one" without having watched the broker run anything. On 2026-09-07 the
 * override was not in effect at all (the endpoint ran with the kind default of
 * 32) and this test still reported a pass. Hence the essay prompt, and hence
 * the assertion below demanding exactly one, not at most one.
 */
class CappedConcurrencyLoadE2E extends GpuBrokerE2EBase {

    private static final String CAPPED_QUEUE = "vllm-Qwen2.5-14B-Instruct-AWQ";
    private static final int CONCURRENT_REQUESTS = 3;

    public static void main(String[] args) throws Exception {
        new CappedConcurrencyLoadE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- CappedConcurrencyLoadE2E ---");
        AtomicInteger observedMaxActive = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CONCURRENT_REQUESTS];
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures[i] = CompletableFuture.runAsync(this::sendLongChatCompletion);
        }

        // 3 essays of ~15s each, serialized by the cap, plus room for cluster load.
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline && !allDone(futures)) {
            observedMaxActive.updateAndGet(current -> Math.max(current, readActiveCount(CAPPED_QUEUE)));
            TimeUnit.MILLISECONDS.sleep(100);
        }
        CompletableFuture.allOf(futures).get(180, TimeUnit.SECONDS);

        require(observedMaxActive.get() >= 1,
                "the run must catch the broker dispatching at least one job, otherwise it has"
                        + " watched nothing; observed active=" + observedMaxActive.get());
        require(observedMaxActive.get() <= 1,
                "max-concurrency=1 override on 192.168.5.14:8000 must hold; observed active="
                        + observedMaxActive.get());

        LOG.info("Observed max active on " + CAPPED_QUEUE + ": " + observedMaxActive.get());
        System.out.println("CappedConcurrencyLoadE2E: PASSED");
    }

    private static boolean allDone(CompletableFuture<Void>[] futures) {
        for (CompletableFuture<Void> f : futures) {
            if (!f.isDone()) {
                return false;
            }
        }
        return true;
    }

    private void sendLongChatCompletion() {
        int status = RestAssured.given().baseUri(BASE_URL)
                .contentType("application/json")
                .body("""
                        {"model":"Qwen2.5-14B-Instruct-AWQ","max_tokens":1000,
                         "messages":[{"role":"user","content":
                           "Write a very long, detailed essay of at least 900 words about the history of computing."}]}
                        """)
                .when().post("/queue/" + CAPPED_QUEUE)
                .then().extract().statusCode();
        require(status == 200, "expected 200 from " + CAPPED_QUEUE + ", got " + status);
    }
}
