package com.scivicslab.gpubroker.model;

/**
 * The raw bytes a GPU broker client sent, forwarded to an AiServiceEndpoint
 * verbatim. The broker never parses {@code bytes}; {@code contentType} is
 * carried through unchanged too.
 */
public record RequestBody(byte[] bytes, String contentType) {
}
