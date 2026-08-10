package com.scivicslab.gpubroker.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * S_proxy (integration): boot the real broker and POST an OpenAI request; the
 * broker forwards it to the in-JVM stub vLLM and relays the response back.
 *
 * <p>Uses an in-JVM HTTP stub for the upstream — no Docker / DevServices /
 * Docker Compose (test policy). The stub binds the port the {@code %test}
 * profile registered as the single node.
 */
@QuarkusTest
@DisplayName("OpenAiCompatibleProxy — end-to-end forward and relay (S_proxy)")
class OpenAiCompatibleProxyIT {

    static StubVllmServer upstream;

    @BeforeAll
    static void startUpstream() {
        upstream = StubVllmServer.start();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void post_chatCompletions_relaysUpstreamResponse() {
        given().header("X-Llm-Priority", "background")
                .contentType(ContentType.JSON)
                .body("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
        .when().post("/v1/chat/completions")
        .then().statusCode(200)
                .body(containsString(upstream.cannedReplyFragment()));
    }
}
