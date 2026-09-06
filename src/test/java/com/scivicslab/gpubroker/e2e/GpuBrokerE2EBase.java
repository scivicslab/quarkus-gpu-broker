package com.scivicslab.gpubroker.e2e;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import io.restassured.RestAssured;

/**
 * Shared HTTP helpers for gpu-broker E2E tests. Plain Java class — no JUnit
 * dependency, per {@code TestingStandard_260404_oo01} §3: E2E tests connect
 * to an already-running environment and are not managed by JUnit or the
 * Maven build lifecycle. Unlike this project's other E2E precedents
 * (k8s-pups, sc-ddbj-e2e), there is no browser to drive — gpu-broker is a
 * REST API with no UI — so no Playwright setup/teardown is needed here.
 */
abstract class GpuBrokerE2EBase {

    protected static final Logger LOG = Logger.getLogger(GpuBrokerE2EBase.class.getName());

    protected static final String BASE_URL = System.getProperty("e2e.base.url", "http://localhost:28003");

    protected String fetchStatus() {
        return RestAssured.given().baseUri(BASE_URL)
                .when().get("/")
                .then().statusCode(200)
                .extract().body().asString();
    }

    protected static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Reads how many jobs {@code queueName} is running, from {@code GET /queues}. */
    protected int readActiveCount(String queueName) {
        return readCounts(queueName)[0];
    }

    /** Reads how many jobs are waiting on {@code queueName}, from {@code GET /queues}. */
    protected int readPendingCount(String queueName) {
        return readCounts(queueName)[1];
    }

    /**
     * Reads {@code activeSlots} and {@code pendingJobs} for one queue.
     *
     * <p>These used to be scraped out of the status page HTML with a regular expression that
     * matched the markup of the day. The page was rebuilt on 2026-09-05 and the expression
     * stopped matching, but it returned zeros rather than failing, so every caller silently
     * read "nothing is running" from then on. {@code GET /queues} answers the same numbers as
     * JSON and does not move when the page is restyled. A queue that is absent is an error
     * here, not another zero.
     */
    private int[] readCounts(String queueName) {
        List<Map<String, Object>> queues = RestAssured.given().baseUri(BASE_URL)
                .when().get("/queues")
                .then().statusCode(200)
                .extract().jsonPath().getList("");
        for (Map<String, Object> queue : queues) {
            if (queueName.equals(queue.get("name"))) {
                return new int[] {
                    ((Number) queue.get("activeSlots")).intValue(),
                    ((Number) queue.get("pendingJobs")).intValue()
                };
            }
        }
        throw new AssertionError("GET /queues does not list a queue named " + queueName);
    }
}
