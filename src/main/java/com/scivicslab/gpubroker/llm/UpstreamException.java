package com.scivicslab.gpubroker.llm;

/**
 * Thrown by {@link UpstreamLlmClient#send} when the upstream vLLM fails
 * mid-flight (node down, connection reset, error status, ...).
 *
 * <p>A {@code NodeActor} that catches this re-submits the job so a healthy node
 * takes it, and withdraws itself from rotation (see {@code HealthAwareNodeSet}).
 */
public class UpstreamException extends RuntimeException {

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
