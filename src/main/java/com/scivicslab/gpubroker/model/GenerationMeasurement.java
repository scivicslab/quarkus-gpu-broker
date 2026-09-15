package com.scivicslab.gpubroker.model;

/**
 * What one finished generation cost, as the broker alone can see it
 * ({@code GenerationRateOnTheStatusPage_260915_oo01}).
 *
 * <p>"Slow" has three causes and only one of them is the model's speed. A vLLM server cannot tell
 * them apart: its own clock starts when the request reaches it, so the time a request spent waiting
 * for a slot is invisible from there. The broker owns the queue, so it is the one place where the
 * three are separable.</p>
 *
 * @param queueName  the queue this ran on
 * @param address    the {@code host:port} that ran it, or {@code null} when it never reached one
 * @param queuedMs   from arriving at the broker to being handed to a worker
 * @param firstMs    from being handed to a worker to the first generated text
 * @param decodeMs   from the first generated text to the last
 * @param tokens     events that carried generated text -- see {@code SseContentScanner.events}
 */
public record GenerationMeasurement(String queueName, String address,
                                    long queuedMs, long firstMs, long decodeMs, long tokens) {
}
