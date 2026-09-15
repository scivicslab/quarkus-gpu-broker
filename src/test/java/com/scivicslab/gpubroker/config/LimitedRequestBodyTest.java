package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.gpubroker.model.RequestBody;

import org.junit.jupiter.api.Test;

class LimitedRequestBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One queue's limits, as an operator would have written them in application.yaml. */
    private record Limit(OptionalInt maxTokens, OptionalDouble repetitionPenalty,
                         OptionalDouble frequencyPenalty) implements BrokerConfig.QueueGenerationLimit {

        static Limit ofMaxTokens(int value) {
            return new Limit(OptionalInt.of(value), OptionalDouble.empty(), OptionalDouble.empty());
        }
    }

    private static RequestBody json(String body) {
        return new RequestBody(body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static JsonNode read(RequestBody body) throws Exception {
        return MAPPER.readTree(body.bytes());
    }

    @Test
    void of_bodyWithoutMaxTokens_getsTheCeiling() throws Exception {
        RequestBody out = LimitedRequestBody.of(
                json("{\"model\":\"m\",\"messages\":[]}"), Limit.ofMaxTokens(8192));
        assertEquals(8192, read(out).get("max_tokens").asInt());
    }

    @Test
    void of_clientAskedForMore_isBroughtDown() throws Exception {
        RequestBody out = LimitedRequestBody.of(
                json("{\"model\":\"m\",\"max_tokens\":100000}"), Limit.ofMaxTokens(8192));
        assertEquals(8192, read(out).get("max_tokens").asInt());
    }

    @Test
    void of_clientAskedForLess_keepsItsOwnValue() {
        RequestBody body = json("{\"model\":\"m\",\"max_tokens\":256}");
        assertSame(body, LimitedRequestBody.of(body, Limit.ofMaxTokens(8192)));
    }

    @Test
    void of_queueWithNoLimit_isForwardedVerbatim() {
        RequestBody body = json("{\"model\":\"m\"}");
        assertSame(body, LimitedRequestBody.of(body, null));
    }

    @Test
    void of_bodyThatIsNotJson_isForwardedVerbatim() {
        // The OCR and embedding paths post multipart form data through the same broker.
        RequestBody body = new RequestBody("--boundary\r\nContent-Disposition: form-data".getBytes(
                StandardCharsets.UTF_8), "multipart/form-data; boundary=boundary");
        assertSame(body, LimitedRequestBody.of(body, Limit.ofMaxTokens(8192)));
    }

    @Test
    void of_penalties_areAddedOnlyWhenTheClientNamedNone() throws Exception {
        Limit limit = new Limit(OptionalInt.empty(), OptionalDouble.of(1.05), OptionalDouble.of(0.1));
        RequestBody out = LimitedRequestBody.of(
                json("{\"model\":\"m\",\"repetition_penalty\":1.5}"), limit);
        JsonNode body = read(out);
        assertEquals(1.5, body.get("repetition_penalty").asDouble(), 1e-9);
        assertEquals(0.1, body.get("frequency_penalty").asDouble(), 1e-9);
    }

    @Test
    void of_everythingAlreadyWithinTheLimits_returnsTheSameBody() throws Exception {
        RequestBody body = json("{\"model\":\"m\",\"max_tokens\":8192}");
        RequestBody out = LimitedRequestBody.of(body, Limit.ofMaxTokens(8192));
        assertSame(body, out);
        assertTrue(read(out).has("max_tokens"));
        assertFalse(read(out).has("frequency_penalty"));
    }
}
