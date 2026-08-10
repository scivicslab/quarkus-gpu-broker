package com.scivicslab.gpubroker.llm;

/**
 * Thrown by {@link AiServiceClient#send} when the real AI service fails
 * mid-flight (endpoint down, connection reset, error status, ...), and also
 * used to fail a {@code ResponseSink} once {@code AiServiceEndpoint.requeue}
 * gives up after too many attempts.
 */
public class AiServiceCallException extends RuntimeException {

    public AiServiceCallException(String message) {
        super(message);
    }

    public AiServiceCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
