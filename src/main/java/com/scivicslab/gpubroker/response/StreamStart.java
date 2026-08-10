package com.scivicslab.gpubroker.response;

import io.smallrye.mutiny.Multi;
import io.vertx.core.buffer.Buffer;

/**
 * What a {@code StreamingResponseSink} hands to {@code ProxyResource} once
 * the real AI service's {@code Content-Type} is known: that header value,
 * paired with the body stream to subscribe to. Together they let {@code
 * RestMulti.fromUniResponse} build the HTTP response only once both are
 * available, while the body stream itself was already created (and may
 * already be buffering) before either was.
 */
public record StreamStart(String contentType, Multi<Buffer> data) {
}
