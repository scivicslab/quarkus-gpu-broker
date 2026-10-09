package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OpenAiCompatResourceTest {

    @Test
    void extractModel_readsModelField() {
        byte[] body = "{\"model\":\"google/gemma-4-26B-A4B-it\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

        assertEquals("google/gemma-4-26B-A4B-it", OpenAiCompatResource.extractModel(body));
    }

    @Test
    void extractModel_missingModelField_returnsNull() {
        assertNull(OpenAiCompatResource.extractModel("{\"messages\":[]}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void extractModel_malformedJson_returnsNull() {
        assertNull(OpenAiCompatResource.extractModel("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void carriesImage_findsAnImageUrlPartInAnyMessage() {
        byte[] body = ("{\"model\":\"m\",\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"read this\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(OpenAiCompatResource.carriesImage(body));
    }

    @Test
    void carriesImage_textOnlyRequestsCarryNone() {
        byte[] plain = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] parts = ("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"hello\"}]}]}").getBytes(StandardCharsets.UTF_8);
        assertFalse(OpenAiCompatResource.carriesImage(plain));
        assertFalse(OpenAiCompatResource.carriesImage(parts));
        assertFalse(OpenAiCompatResource.carriesImage("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void refusesImage_onlyWhenTheTableSaysNoAndTheRequestCarriesOne() {
        Map<String, Boolean> table = Map.of(
                "chat-qwen3.8-flash-next-tensorfold", false,
                "chat-qwen3.8-flash-next", true);
        byte[] image = ("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":"
                + "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}]}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] text = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(OpenAiCompatResource.refusesImage(table, "chat-qwen3.8-flash-next-tensorfold", image));
        assertFalse(OpenAiCompatResource.refusesImage(table, "chat-qwen3.8-flash-next-tensorfold", text));
        assertFalse(OpenAiCompatResource.refusesImage(table, "chat-qwen3.8-flash-next", image));
        assertFalse(OpenAiCompatResource.refusesImage(table, "chat-not-in-the-table", image),
                "a queue the table does not list is unknown, and is sent on");
    }
}
