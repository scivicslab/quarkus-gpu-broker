package com.scivicslab.gpubroker.it;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * Runs {@code quarkus-gpu-broker} as the real packaged jar, launched on bare
 * metal the same way AI-workspace launches it — {@code quarkus-gpu-broker}
 * is never deployed to k8s, so a k8s-dev IT would test a shape this
 * application does not run in. Talks to the real W206 cluster
 * (192.168.5.0/26). See {@code E2ETestPlan_260810_oo01} for why this is
 * neither a k8s-dev IT nor a one-off manual bash check.
 *
 * <p>The {@code config/application.yaml} override this app needs at boot
 * ({@code src/test/resources/it/application.yaml}) is copied into place by
 * a {@code pre-integration-test}-bound Maven plugin execution (see pom.xml),
 * not by test code — a JUnit5 {@code @BeforeAll}/static block cannot
 * guarantee it runs before {@code QuarkusIntegrationTestExtension} launches
 * the jar (Jupiter loads test classes without initializing them during
 * discovery, so class-load timing raced the extension's own launch
 * intermittently). Maven's lifecycle phases give a hard ordering guarantee
 * that JUnit5 extension order does not.
 */
@QuarkusIntegrationTest
@DisplayName("quarkus-gpu-broker against the real W206 cluster")
class GpuBrokerRealClusterIT {

    @Test
    @DisplayName("startup discovery finds the real cluster's endpoints (scenarios 1, 12)")
    void discoversRealClusterEndpoints() {
        String status = fetchStatus();
        assertTrue(status.contains("embedding-e5large"), "expected embedding-e5large queue: " + status);
        assertTrue(status.contains("vllm-Qwen2.5-14B-Instruct-AWQ"), "expected the capped Qwen queue: " + status);
        // google/gemma-4-26B-A4B-it contains '/' — queueName must be sanitized, not just present.
        assertTrue(status.contains("vllm-google-gemma-4-26B-A4B-it"), "expected sanitized gemma-4 queueName: " + status);
        assertTrue(!status.contains("vllm-google/gemma"), "queueName must not retain '/': " + status);
    }

    @Test
    @DisplayName("a real embedding job round-trips through the queue (scenario 3-equivalent, sync path)")
    void embeddingJobRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"model\":\"e5-large\",\"input\":\"GpuBrokerRealClusterIT smoke test\"}")
                .when().post("/queue/embedding-e5large")
                .then().statusCode(200)
                .body("data[0].embedding.size()", org.hamcrest.Matchers.equalTo(1024));
    }

    @Test
    @DisplayName("simple load test: the max-concurrency=1 override on 192.168.5.14 holds under concurrent load")
    void cappedNodeNeverExceedsConcurrencyOne() throws Exception {
        int concurrentRequests = 5;
        AtomicInteger observedMaxActive = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = new CompletableFuture[concurrentRequests];
        for (int i = 0; i < concurrentRequests; i++) {
            futures[i] = CompletableFuture.runAsync(GpuBrokerRealClusterIT::sendShortChatCompletion);
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline && !allDone(futures)) {
            observedMaxActive.updateAndGet(current -> Math.max(current, readCappedQueueActiveCount()));
            TimeUnit.MILLISECONDS.sleep(100);
        }
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

        assertTrue(observedMaxActive.get() <= 1,
                "max-concurrency=1 override on 192.168.5.14:8000 must hold; observed active="
                        + observedMaxActive.get());
    }

    private static boolean allDone(CompletableFuture<Void>[] futures) {
        for (CompletableFuture<Void> f : futures) {
            if (!f.isDone()) {
                return false;
            }
        }
        return true;
    }

    private static void sendShortChatCompletion() {
        given()
                .contentType("application/json")
                .body("""
                        {"model":"Qwen2.5-14B-Instruct-AWQ","max_tokens":5,
                         "messages":[{"role":"user","content":"reply with one word"}]}
                        """)
                .when().post("/queue/vllm-Qwen2.5-14B-Instruct-AWQ")
                .then().statusCode(200);
    }

    private static int readCappedQueueActiveCount() {
        Matcher m = Pattern.compile("vllm-Qwen2\\.5-14B-Instruct-AWQ</strong> — active: (\\d+)")
                .matcher(fetchStatus());
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String fetchStatus() {
        return given().when().get("/").then().statusCode(200).extract().body().asString();
    }
}
