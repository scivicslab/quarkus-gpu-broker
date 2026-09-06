package com.scivicslab.gpubroker.config;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * YomiToku's OCR endpoint.
 *
 * <p>The queue is routed to {@code /ocr/markdown} rather than {@code /ocr}. Both read the same page
 * with the same engine, but {@code /ocr} returns only the analyzer's paragraphs, and a page whose
 * content is a table has no paragraphs — on a measured page of a scanned Japanese textbook it
 * returned the running head, a "continued" arrow and the page number, and dropped an eleven-row
 * table entirely. {@code /ocr/markdown} exports the same analysis as Markdown and carries the table.
 * See {@code YomiTokuMarkdownEndpoint_260907_oo01}.</p>
 */
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
        return "/ocr/markdown";
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
