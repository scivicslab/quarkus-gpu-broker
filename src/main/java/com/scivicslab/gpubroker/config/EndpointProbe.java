package com.scivicslab.gpubroker.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One AI service kind the broker knows how to discover and talk to (formerly
 * {@code EndpointKind} — renamed because this is an active prober, not a
 * static classification; see {@code CapabilityConfig_260810_oo01} "なぜ
 * EndpointKind から EndpointProbe へ改名したか").
 *
 * <p>Carries the protocol facts needed to probe for and talk to this kind
 * (conventional port, probe path, request path, {@code queueName}
 * derivation, default concurrency ceiling). Implementations are {@code
 * @ApplicationScoped} CDI beans, one per kind, injected as {@code
 * List<EndpointProbe>} by {@code JobQueueRegistry}.
 *
 * <p>{@link #survey} is the whole-cluster query for this one kind: given a
 * set of node IPs, probe every {@code nodeIp:conventionalPort()} in
 * parallel and return an {@link EndpointInfo} for every one that answered.
 * It is stateless — called fresh at startup and on every poll; the caller is
 * responsible for diffing successive results (see {@code
 * CapabilityConfig_260810_oo01} "なぜ survey は状態を持たないか").
 */
public interface EndpointProbe {

    Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    HttpClient PROBE_CLIENT = HttpClient.newHttpClient();

    int conventionalPort();

    String probePath();

    /** The URL path real job requests go to (e.g. {@code /v1/chat/completions}). Not used by {@code survey} itself. */
    String requestPath();

    /** Interpret a successful probe's response body into a {@code queueName}, or empty if the shape doesn't match. */
    Optional<String> deriveQueueName(String probeResponseBody);

    /** Default {@code maxConcurrency} for this kind; {@code broker.capabilities.<host:port>.max-concurrency} overrides it per instance. */
    int defaultMaxConcurrency();

    /**
     * Probes every {@code nodeIp:conventionalPort()} in parallel and returns an
     * {@link EndpointInfo} (address, queueName, resolved maxConcurrency) for
     * each one that answered as this kind.
     */
    default List<EndpointInfo> survey(List<String> nodeIps, Map<String, BrokerConfig.EndpointCapability> capabilities) {
        List<EndpointInfo> found = new ArrayList<>();
        try (ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<EndpointInfo>>> futures = new ArrayList<>();
            for (String nodeIp : nodeIps) {
                String address = nodeIp + ":" + conventionalPort();
                futures.add(probes.submit(() -> probeOne(address, capabilities)));
            }
            for (Future<Optional<EndpointInfo>> future : futures) {
                future.get().ifPresent(found::add);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("probe task failed unexpectedly", e);
        }
        return found;
    }

    private Optional<EndpointInfo> probeOne(String address, Map<String, BrokerConfig.EndpointCapability> capabilities) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address + probePath()))
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = PROBE_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Optional.empty();   // not reachable — not this kind at this address
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return Optional.empty();
        }
        Optional<String> queueName = deriveQueueName(response.body());
        if (queueName.isEmpty() || !requestPathExists(address)) {
            return Optional.empty();
        }
        return Optional.of(new EndpointInfo(address, queueName.get(), resolveMaxConcurrency(address, capabilities)));
    }

    /**
     * Whether {@link #requestPath()} actually exists at {@code address} — {@link #probePath()}
     * succeeding is not enough evidence by itself: a node can answer a generic health check (e.g.
     * {@code "/"}) while running a service with a different, incompatible API shape than the one
     * {@code requestPath()} names. A bare {@code GET} on {@code requestPath()} cannot exercise a
     * real request (most of these paths only accept {@code POST}), so this only distinguishes "the
     * path exists" ({@code 2xx}, or {@code 405} for a path that rejects {@code GET} specifically)
     * from "the path does not exist" ({@code 404} or anything else) — verified against every
     * currently-known-good node for every {@code EndpointProbe} kind, which all return {@code 405}
     * here (see {@code EmbeddingDiscoveryFix_260822_oo01}).
     */
    private boolean requestPathExists(String address) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address + requestPath()))
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<Void> response = PROBE_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return (status >= 200 && status < 300) || status == 405;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private int resolveMaxConcurrency(String address, Map<String, BrokerConfig.EndpointCapability> capabilities) {
        BrokerConfig.EndpointCapability override = capabilities.get(address);
        if (override != null && override.maxConcurrency().isPresent()) {
            return override.maxConcurrency().getAsInt();
        }
        return defaultMaxConcurrency();
    }
}
