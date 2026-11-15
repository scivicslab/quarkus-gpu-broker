package com.scivicslab.gpubroker.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ManagementAuthentication_261115_oo01}: PUT /capacity/{address} needs a token from the realm with
 * the role {@code broker-capacity-writer}; every other endpoint stays open, including for a client that
 * sends an Authorization header of its own. The signing keys are served by an in-process stub.
 */
@QuarkusTest
@QuarkusTestResource(value = ManagementAuthIT.Up.class, restrictToAnnotatedClass = true)
class ManagementAuthIT {

    public static class Up extends JwksStub {
        @Override boolean up() { return true; }
    }

    private static final String BODY = "{\"maxConcurrency\": 32, \"serverStartTime\": 1790000000.5}";
    private static final String PATH = "/capacity/127.0.0.1:8000";

    private static io.restassured.specification.RequestSpecification put() {
        return given().contentType("application/json").body(BODY);
    }

    @Test
    void withoutAToken_theDeclarationIsRefused() {
        put().when().put(PATH).then().statusCode(401);
    }

    @Test
    void withTheRole_theDeclarationIsAccepted() {
        put().auth().oauth2(JwksStub.token(List.of(JwksStub.ROLE)))
                .when().put(PATH).then().statusCode(204);
    }

    @Test
    void aValidTokenWithoutTheRole_isForbidden() {
        put().auth().oauth2(JwksStub.token(List.of("offline_access")))
                .when().put(PATH).then().statusCode(403);
    }

    @Test
    void aTokenSignedByAnotherKey_isRefused() {
        String forged = JwksStub.token(List.of(JwksStub.ROLE), JwksStub.ISSUER, Instant.now().plusSeconds(300), true);
        put().auth().oauth2(forged).when().put(PATH).then().statusCode(401);
    }

    @Test
    void anExpiredToken_isRefused() {
        String old = JwksStub.token(List.of(JwksStub.ROLE), JwksStub.ISSUER, Instant.now().minusSeconds(120), false);
        put().auth().oauth2(old).when().put(PATH).then().statusCode(401);
    }

    @Test
    void aTokenFromAnotherIssuer_isRefused() {
        String other = JwksStub.token(List.of(JwksStub.ROLE), "https://elsewhere.test/realms/x", Instant.now().plusSeconds(300), false);
        put().auth().oauth2(other).when().put(PATH).then().statusCode(401);
    }

    @Test
    void statusReadsStayOpen() {
        given().when().get("/").then().statusCode(200);
        given().when().get("/queues").then().statusCode(200);
        given().when().get("/capacity").then().statusCode(200);
        given().when().get("/v1/models").then().statusCode(200);
    }

    @Test
    void anAuthorizationHeaderOfAnotherKindDoesNotCloseAnOpenEndpoint() {
        // An OpenAI SDK or Codex sends "Authorization: Bearer <something>"; the broker must not judge it.
        given().header("Authorization", "Bearer sk-not-a-token").when().get("/queues").then().statusCode(200);
        given().header("Authorization", "Bearer sk-not-a-token").when().get("/v1/models").then().statusCode(200);
        given().header("Authorization", "Bearer sk-not-a-token").contentType("application/json")
                .body("{\"model\":\"none\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                .when().post("/v1/chat/completions")
                .then().statusCode(not(anyOf(is(401), is(403))));
    }
}
