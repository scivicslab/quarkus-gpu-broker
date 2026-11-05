package com.scivicslab.gpubroker.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RecordingResponseSink;
import com.scivicslab.gpubroker.model.RequestBody;

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

    /**
     * A server that refuses the request (vLLM's 400 for a malformed message) is reported with its own
     * status, so the broker answers the client with that status, not a 200 carrying an error body.
     */
    @Test
    void aRefusalOfTheServer_isRelayedWithItsStatus() throws Exception {
        byte[] refusal = "{\"error\":{\"message\":\"duplicate field `reasoning`\",\"code\":400}}"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, refusal.length);
            exchange.getResponseBody().write(refusal);
            exchange.close();
        });
        server.start();
        try {
            RecordingResponseSink sink = new RecordingResponseSink();
            Job job = Job.first(new RequestBody("{}".getBytes(StandardCharsets.UTF_8), "application/json"),
                    Priority.FOREGROUND, sink);
            new HttpAiServiceClient().send("127.0.0.1:" + server.getAddress().getPort(), "/v1/chat/completions", job);

            assertEquals(400, sink.status());
            assertEquals(new String(refusal, StandardCharsets.UTF_8), sink.bodyAsString());
        } finally {
            server.stop(0);
        }
    }
}
