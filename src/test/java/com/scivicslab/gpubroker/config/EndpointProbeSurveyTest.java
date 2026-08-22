package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@code EndpointProbe.survey} against a real (in-process, JDK-only) HTTP
 * server — no Docker/DevServices, per this project's testing policy. Binds
 * to an OS-assigned ephemeral port (not {@code VllmChatProbe}'s fixed 8000,
 * which may already be in use by a real service on a shared devbox) via a
 * minimal test-local {@link EndpointProbe} implementation.
 *
 * <p>Covers the one behavior that matters most from today's redesign:
 * {@code broker.capabilities.<host:port>.max-concurrency} overriding {@code
 * defaultMaxConcurrency()} per instance (the {@code 192.168.5.14} case, see
 * {@code CapabilityConfig_260810_oo01}).
 */
@DisplayName("EndpointProbe.survey — real HTTP probing and per-instance maxConcurrency override")
class EndpointProbeSurveyTest {

    private HttpServer server;
    private FakeProbe probe;

    @BeforeEach
    void startFakeEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // OS-assigned free port
        server.createContext("/probe", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // A real request path that exists but rejects GET (405) -- what every currently-known-good
        // node returns for its own requestPath() (see EmbeddingDiscoveryFix_260822_oo01).
        server.createContext("/real-request-path", exchange -> {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
        });
        server.start();
        probe = new FakeProbe(server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeEndpoint() {
        server.stop(0);
    }

    @Test
    void noOverride_usesDefaultMaxConcurrency() {
        List<EndpointInfo> found = probe.survey(List.of("127.0.0.1"), Map.of());

        assertEquals(1, found.size());
        EndpointInfo info = found.get(0);
        assertEquals("127.0.0.1:" + probe.conventionalPort(), info.address());
        assertEquals("fake-queue", info.queueName());
        assertEquals(4, info.maxConcurrency());   // FakeProbe.defaultMaxConcurrency()
    }

    @Test
    void perInstanceOverride_winsOverDefault() {
        String address = "127.0.0.1:" + probe.conventionalPort();
        Map<String, BrokerConfig.EndpointCapability> capabilities =
                Map.of(address, new StubEndpointCapability(1));

        List<EndpointInfo> found = probe.survey(List.of("127.0.0.1"), capabilities);

        assertEquals(1, found.size());
        assertEquals(1, found.get(0).maxConcurrency());   // 192.168.5.14-style override, not the default 4
    }

    @Test
    void unreachableNode_isSilentlyOmitted() {
        stopFakeEndpoint();   // nothing listening on this port anymore

        List<EndpointInfo> found = probe.survey(List.of("127.0.0.1"), Map.of());

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("probePath succeeding is not enough: requestPath must exist (405 on GET) too")
    void requestPathReturning405_isTreatedAsExisting_nodeIsRegistered() {
        FakeProbe realRequestPath = new FakeProbe(server.getAddress().getPort(), "/real-request-path");

        List<EndpointInfo> found = realRequestPath.survey(List.of("127.0.0.1"), Map.of());

        assertEquals(1, found.size(), "requestPath answering 405 to GET means the path exists — node should register");
    }

    @Test
    @DisplayName("a probePath that answers but a requestPath that 404s means the node is a different, incompatible service")
    void requestPathMissing_nodeIsExcluded_evenThoughProbePathSucceeded() {
        FakeProbe wrongRequestPath = new FakeProbe(server.getAddress().getPort(), "/no-such-path-on-this-server");

        List<EndpointInfo> found = wrongRequestPath.survey(List.of("127.0.0.1"), Map.of());

        assertTrue(found.isEmpty(),
                "probePath succeeded but requestPath 404s -- this is the 192.168.5.14:8012 bug "
                        + "(EmbeddingDiscoveryFix_260822_oo01): a node can pass a generic health check "
                        + "while running an incompatible service, and must not be registered");
    }

    /** A minimal {@code EndpointProbe} whose port is chosen at test time, unlike the real kinds' fixed
     *  ports. {@code requestPathValue} defaults to the same {@code /probe} path {@code probePath()}
     *  uses, so existing tests (which don't care about the {@code requestPath} distinction) are
     *  unaffected; tests exercising that distinction pass a different value explicitly. */
    private record FakeProbe(int conventionalPort, String requestPathValue) implements EndpointProbe {
        FakeProbe(int conventionalPort) {
            this(conventionalPort, "/probe");
        }

        @Override
        public String probePath() {
            return "/probe";
        }

        @Override
        public String requestPath() {
            return requestPathValue;
        }

        @Override
        public Optional<String> deriveQueueName(String probeResponseBody) {
            return Optional.of("fake-queue");
        }

        @Override
        public int defaultMaxConcurrency() {
            return 4;
        }
    }

    /** Minimal {@code EndpointCapability} stub — only {@code maxConcurrency} is exercised here. */
    private record StubEndpointCapability(int declaredMaxConcurrency) implements BrokerConfig.EndpointCapability {
        @Override
        public OptionalInt maxConcurrency() {
            return OptionalInt.of(declaredMaxConcurrency);
        }

        @Override
        public OptionalInt maxContextLength() {
            return OptionalInt.empty();
        }

        @Override
        public Optional<Boolean> thinkingModeSupported() {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> toolCallingSupported() {
            return Optional.empty();
        }
    }
}
