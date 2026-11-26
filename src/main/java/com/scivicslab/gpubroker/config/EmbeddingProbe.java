package com.scivicslab.gpubroker.config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;

/** The embedding server's endpoint. */
@ApplicationScoped
public class EmbeddingProbe implements EndpointProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public int conventionalPort() {
        return 8012;
    }

    @Override
    public String probePath() {
        return "/";
    }

    @Override
    public String requestPath() {
        return "/v1/embeddings";
    }

    @Override
    public int defaultMaxConcurrency() {
        return 8;   // measured 30 concurrent with no errors; kept conservative below that
    }

    @Override
    public Optional<String> deriveQueueName(String probeResponseBody) {
        return Optional.of("embedding-e5large");
    }

    /**
     * {@code GET /} and a path that is not 404 are answered by an Ollama chat server too ({@code POST /v1/embeddings}
     * gives 501 "This server does not support embeddings"), so only a server that returns 200 and a {@code data}
     * array for a real one-word request is an embedding server.
     */
    @Override
    public boolean answersAsThisKind(String address) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address + requestPath()))
                .timeout(PROBE_TIMEOUT)
                .version(java.net.http.HttpClient.Version.HTTP_1_1)   // the default h2c upgrade makes the e5 server drop the body (422)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":[\"hello\"]}"))
                .build();
        try {
            HttpResponse<String> response = PROBE_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && MAPPER.readTree(response.body()).path("data").isArray();
        } catch (JsonProcessingException e) {
            return false;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
