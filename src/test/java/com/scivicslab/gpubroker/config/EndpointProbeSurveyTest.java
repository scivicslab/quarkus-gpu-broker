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

    /** A minimal {@code EndpointProbe} whose port is chosen at test time, unlike the real kinds' fixed ports. */
    private record FakeProbe(int conventionalPort) implements EndpointProbe {
        @Override
        public String probePath() {
            return "/probe";
        }

        @Override
        public String requestPath() {
            return "/probe";
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
