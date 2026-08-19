package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.restassured.RestAssured;

/**
 * Simple load test: fires concurrent requests at 192.168.5.14:8000 (a shared,
 * non-dedicated GPU node) and confirms the already-running broker's
 * {@code max-concurrency=1} override for it — configured via AI-workspace's
 * {@code config/application.yaml}, see {@code CapabilityConfig_260810_oo01}
 * — actually holds, not just that it is declared.
 */
class CappedConcurrencyLoadE2E extends GpuBrokerE2EBase {

    private static final String CAPPED_QUEUE = "vllm-Qwen2.5-14B-Instruct-AWQ";
    private static final int CONCURRENT_REQUESTS = 5;

    public static void main(String[] args) throws Exception {
        new CappedConcurrencyLoadE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- CappedConcurrencyLoadE2E ---");
        AtomicInteger observedMaxActive = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[CONCURRENT_REQUESTS];
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures[i] = CompletableFuture.runAsync(this::sendShortChatCompletion);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && !allDone(futures)) {
            observedMaxActive.updateAndGet(current -> Math.max(current, readCappedQueueActiveCount()));
            TimeUnit.MILLISECONDS.sleep(100);
        }
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

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

    private void sendShortChatCompletion() {
        int status = RestAssured.given().baseUri(BASE_URL)
                .contentType("application/json")
                .body("""
                        {"model":"Qwen2.5-14B-Instruct-AWQ","max_tokens":5,
                         "messages":[{"role":"user","content":"reply with one word"}]}
                        """)
                .when().post("/queue/" + CAPPED_QUEUE)
                .then().extract().statusCode();
        require(status == 200, "expected 200 from " + CAPPED_QUEUE + ", got " + status);
    }

    private int readCappedQueueActiveCount() {
        Matcher m = Pattern.compile(Pattern.quote(CAPPED_QUEUE) + "</strong> — active: (\\d+)")
                .matcher(fetchStatus());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
