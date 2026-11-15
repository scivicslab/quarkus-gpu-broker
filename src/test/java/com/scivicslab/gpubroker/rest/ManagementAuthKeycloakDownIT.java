package com.scivicslab.gpubroker.rest;

import static io.restassured.RestAssured.given;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code ManagementAuthentication_261115_oo01}: when the realm cannot be reached, the broker still
 * starts and serves, and a management call is refused: it fails closed. The stub leaves its port closed.
 */
@QuarkusTest
@QuarkusTestResource(value = ManagementAuthKeycloakDownIT.Down.class, restrictToAnnotatedClass = true)
class ManagementAuthKeycloakDownIT {

    public static class Down extends JwksStub {
        @Override boolean up() { return false; }
    }

    @Test
    void theBrokerServesWithoutKeycloak() {
        given().when().get("/").then().statusCode(200);
        given().when().get("/queues").then().statusCode(200);
        given().when().get("/capacity").then().statusCode(200);
    }

    @Test
    void aManagementCallWithATokenIsRefusedAsUnavailable_notAccepted() {
        given().contentType("application/json")
                .body("{\"maxConcurrency\": 32, \"serverStartTime\": 1790000000.5}")
                .auth().oauth2(JwksStub.token(List.of(JwksStub.ROLE)))
                .when().put("/capacity/127.0.0.1:8000").then().statusCode(503);
    }

    @Test
    void aManagementCallWithoutATokenIsRefused() {
        given().contentType("application/json")
                .body("{\"maxConcurrency\": 32, \"serverStartTime\": 1790000000.5}")
                .when().put("/capacity/127.0.0.1:8000").then().statusCode(401);
    }
}
