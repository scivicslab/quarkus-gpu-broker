package com.scivicslab.gpubroker.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.net.http.HttpClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The default {@code HttpClient} attempts an h2c (HTTP/2 cleartext) upgrade,
 * which corrupts {@code multipart/form-data} bodies sent to uvicorn/FastAPI
 * backends (YomiToku, Marker, embedding) — confirmed against real nodes
 * (see {@code 018_concurrency_control/000_PerEndpointConcurrency_260810_oo01}).
 * This guards against silently reverting to {@code HttpClient.newHttpClient()}.
 */
@DisplayName("HttpAiServiceClient — forces HTTP/1.1")
class HttpAiServiceClientTest {

    @Test
    void underlyingClient_isPinnedToHttp11() throws Exception {
        HttpAiServiceClient client = new HttpAiServiceClient();

        Field httpField = HttpAiServiceClient.class.getDeclaredField("http");
        httpField.setAccessible(true);
        HttpClient http = (HttpClient) httpField.get(client);

        assertEquals(HttpClient.Version.HTTP_1_1, http.version());
    }
}
