package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;

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
}
