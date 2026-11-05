package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.gpubroker.model.RequestBody;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReplayedReasoningBody — one name for the reasoning of a replayed turn")
class ReplayedReasoningBodyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RequestBody body(String json) {
        return new RequestBody(json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** What Hermes Agent sends after a tool call, which vLLM refused with "duplicate field `reasoning`". */
    @Test
    void aTurnWithBothNames_keepsReasoningOnly() throws Exception {
        RequestBody in = body("{\"model\":\"m\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"list files\"},"
                + "{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"use ls\",\"reasoning_content\":\"use ls\","
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"terminal\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"a.txt\"}]}");

        JsonNode turn = MAPPER.readTree(ReplayedReasoningBody.of(in).bytes()).path("messages").get(1);

        assertTrue(turn.has("reasoning"));
        assertFalse(turn.has("reasoning_content"));
        assertTrue(turn.has("tool_calls"));
    }

    @Test
    void aTurnWithOneName_isLeftAsItCame() {
        RequestBody onlyContent = body("{\"messages\":[{\"role\":\"assistant\",\"content\":\"x\",\"reasoning_content\":\"r\"}]}");
        RequestBody onlyReasoning = body("{\"messages\":[{\"role\":\"assistant\",\"content\":\"x\",\"reasoning\":\"r\"}]}");

        assertSame(onlyContent, ReplayedReasoningBody.of(onlyContent));
        assertSame(onlyReasoning, ReplayedReasoningBody.of(onlyReasoning));
    }

    @Test
    void aBodyThatIsNotAChatRequest_isLeftAsItCame() {
        RequestBody embedding = body("{\"input\":\"text\"}");
        RequestBody multipart = new RequestBody("--boundary\r\n".getBytes(StandardCharsets.UTF_8), "multipart/form-data");

        assertSame(embedding, ReplayedReasoningBody.of(embedding));
        assertSame(multipart, ReplayedReasoningBody.of(multipart));
    }
}
