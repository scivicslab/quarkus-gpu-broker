package com.scivicslab.gpubroker.rest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/**
 * In-JVM stub vLLM used by the proxy integration test. It is a plain
 * {@link HttpServer} on a fixed port — NOT Docker, DevServices, or Docker
 * Compose (test policy) — that answers {@code POST /v1/chat/completions} with a
 * small canned SSE body ending in {@code data: [DONE]}.
 *
 * <p>The {@code %test} profile registers {@code http://localhost:28099} as the
 * single node, so the broker forwards here during the test.
 */
final class StubVllmServer {

    static final int PORT = 28099;
    static final String REPLY_FRAGMENT = "HELLO_FROM_STUB";

    private final HttpServer server;

    private StubVllmServer(HttpServer server) {
        this.server = server;
    }

    static StubVllmServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"" + REPLY_FRAGMENT + "\"}}]}\n"
                        + "data: [DONE]\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.setExecutor(null);
            server.start();
            return new StubVllmServer(server);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start stub vLLM on port " + PORT, e);
        }
    }

    void stop() {
        server.stop(0);
    }

    String cannedReplyFragment() {
        return REPLY_FRAGMENT;
    }
}
