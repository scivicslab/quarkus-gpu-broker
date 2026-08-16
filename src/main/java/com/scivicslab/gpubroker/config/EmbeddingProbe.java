package com.scivicslab.gpubroker.config;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/** The embedding server's endpoint. */
@ApplicationScoped
public class EmbeddingProbe implements EndpointProbe {

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
}
