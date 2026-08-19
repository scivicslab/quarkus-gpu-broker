package com.scivicslab.gpubroker.e2e;

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
}
