package com.scivicslab.gpubroker.config;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/** Marker's OCR endpoint. */
@ApplicationScoped
public class MarkerOcrProbe implements EndpointProbe {

    @Override
    public int conventionalPort() {
        return 8001;
    }

    @Override
    public String probePath() {
        return "/";
    }

    @Override
    public String requestPath() {
        return "/marker/upload";   // /convert 404s on the real service — fixed after direct verification
    }

    @Override
    public int defaultMaxConcurrency() {
        return 1;   // measured intermittent 500 under 2 concurrent requests — a reliability constraint, not tuning
    }

    @Override
    public Optional<String> deriveQueueName(String probeResponseBody) {
        return Optional.of("marker-ocr");
    }
}
