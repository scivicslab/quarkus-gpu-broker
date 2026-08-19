package com.scivicslab.gpubroker.e2e;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.restassured.RestAssured;
import io.restassured.response.Response;

/**
 * {@code JobResultStore} TTL expiry (scenario 9 of {@code
 * E2ETestPlan_260810_oo01}): a {@code jobId} that is never {@code GET}
 * disappears once its 1-hour TTL passes — needs no stub or broker restart,
 * only real wall-clock time.
 *
 * <p>{@code JobResultStore.sweep()} runs every 10 minutes ({@code
 * @Scheduled(every = "10m")}), not on access, so the wait is TTL (1h) plus
 * up to one more sweep interval — about 70 minutes total. That is too slow
 * to run on every E2E pass, so this class is deliberately NOT called from
 * {@link GpuBrokerE2ERunner}; run it on its own when this behavior needs
 * re-checking:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.gpubroker.e2e.JobResultTtlE2E \
 *     -Dexec.classpathScope=test \
 *     -De2e.base.url=http://localhost:28003
 * </pre>
 */
class JobResultTtlE2E extends GpuBrokerE2EBase {

    private static final String QUEUE = "embedding-e5large";
    private static final Duration WAIT = Duration.ofMinutes(70); // 1h TTL + one 10m sweep + margin

    public static void main(String[] args) throws Exception {
        new JobResultTtlE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- JobResultTtlE2E --- (takes ~" + WAIT.toMinutes() + " minutes)");

        Response submitResponse = RestAssured.given().baseUri(BASE_URL)
                .contentType("application/json")
                .body("{\"model\":\"e5-large\",\"input\":\"JobResultTtlE2E smoke test\"}")
                .when().post("/jobs/" + QUEUE);
        require(submitResponse.statusCode() == 202,
                "expected 202, got " + submitResponse.statusCode() + ": " + submitResponse.asString());
        String jobId = submitResponse.asString();
        LOG.info("Submitted jobId=" + jobId + "; waiting " + WAIT.toMinutes() + " minutes without polling it");

        TimeUnit.SECONDS.sleep(WAIT.toSeconds());

        int status = RestAssured.given().baseUri(BASE_URL)
                .when().get("/jobs/" + QUEUE + "/" + jobId)
                .then().extract().statusCode();
        require(status == 404, "expected 404 for an expired, never-polled jobId, got " + status);

        System.out.println("JobResultTtlE2E: PASSED");
    }
}
