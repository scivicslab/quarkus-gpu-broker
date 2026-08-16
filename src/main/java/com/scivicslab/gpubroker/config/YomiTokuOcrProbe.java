package com.scivicslab.gpubroker.config;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/** YomiToku's OCR endpoint. */
@ApplicationScoped
public class YomiTokuOcrProbe implements EndpointProbe {

    @Override
    public int conventionalPort() {
        return 8013;
    }

    @Override
    public String probePath() {
        return "/";
    }

    @Override
    public String requestPath() {
        return "/ocr";
    }

    @Override
    public int defaultMaxConcurrency() {
        return 1;   // no measured benefit on the currently deployed GPU — not a proven architectural ceiling
    }

    @Override
    public Optional<String> deriveQueueName(String probeResponseBody) {
        return Optional.of("yomitoku-ocr");
    }
}
