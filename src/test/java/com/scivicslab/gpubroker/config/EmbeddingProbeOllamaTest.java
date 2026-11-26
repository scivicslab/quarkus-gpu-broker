package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * An Ollama server answers {@code GET /} with 200 and {@code POST /v1/embeddings} with 501
 * ("This server does not support embeddings"). That is not an embedding server: only a server that
 * accepts a real embedding request and returns a {@code data} array is one. Measured on 5.13:11435
 * (Inkling-2-Mini) and on the real e5large (5.18:8012).
 */
class EmbeddingProbeOllamaTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int embeddingsStatus, String embeddingsBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> reply(exchange, 200, "Ollama is running"));
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            reply(exchange, embeddingsStatus, embeddingsBody);
        });
        server.start();
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void aServerThatAnswers501OnEmbeddingsIsNotAnEmbeddingServer() throws IOException {
        String address = serve(501, "{\"error\":\"This server does not support embeddings\"}");
        assertEquals(List.of(), new EmbeddingProbe().survey(List.of(address), Map.of()));
    }

    @Test
    void aServerThatReturnsADataArrayIsAnEmbeddingServer() throws IOException {
        String address = serve(200, "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"embedding\":[0.1]}]}");
        List<EndpointInfo> found = new EmbeddingProbe().survey(List.of(address), Map.of());
        assertEquals(1, found.size());
        assertEquals("embedding-e5large", found.get(0).queueName());
    }

    @Test
    void a200WithoutADataArrayIsNotAnEmbeddingServer() throws IOException {
        String address = serve(200, "{\"ok\":true}");
        assertTrue(new EmbeddingProbe().survey(List.of(address), Map.of()).isEmpty());
    }

    /**
     * The real e5large server (uvicorn) loses the body of a request that carries the JDK client's default
     * {@code Upgrade: h2c} header and answers 422 "body missing" (seen from inside the broker Pod on 2026-11-26,
     * while curl with the same body got 200). The probe has to ask in HTTP/1.1.
     */
    @Test
    void aServerThatDropsTheBodyOfAnH2cUpgradeRequestIsStillFoundAsAnEmbeddingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> reply(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            boolean upgrade = exchange.getRequestHeaders().getFirst("Upgrade") != null;
            if (upgrade || body.length == 0) {
                reply(exchange, 422, "{\"detail\":[{\"type\":\"missing\"}]}");
            } else {
                reply(exchange, 200, "{\"object\":\"list\",\"data\":[{\"embedding\":[0.1]}]}");
            }
        });
        server.start();
        String address = "127.0.0.1:" + server.getAddress().getPort();
        assertEquals(1, new EmbeddingProbe().survey(List.of(address), Map.of()).size());
    }
}
