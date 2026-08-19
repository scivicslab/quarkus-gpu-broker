package com.scivicslab.gpubroker.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;

/**
 * A real embedding job round-trips through the queue against the already-running
 * broker (scenario 3-equivalent of {@code E2ETestPlan_260810_oo01}, sync path).
 */
class EmbeddingRoundTripE2E extends GpuBrokerE2EBase {

    public static void main(String[] args) throws Exception {
        new EmbeddingRoundTripE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- EmbeddingRoundTripE2E ---");

        Response response = RestAssured.given().baseUri(BASE_URL)
                .contentType("application/json")
                .body("{\"model\":\"e5-large\",\"input\":\"GpuBrokerE2ERunner smoke test\"}")
                .when().post("/queue/embedding-e5large");

        require(response.statusCode() == 200, "expected 200, got " + response.statusCode() + ": " + response.asString());
        int vectorLength = response.jsonPath().getList("data[0].embedding").size();
        require(vectorLength == 1024, "expected a 1024-dim embedding vector, got " + vectorLength);

        LOG.info("Embedding round trip confirmed, vector length=" + vectorLength);
        System.out.println("EmbeddingRoundTripE2E: PASSED");
    }
}
