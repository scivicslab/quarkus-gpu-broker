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
 * @param characters characters of generated text; the unit that survives a change of model
 * @param ranAlone   whether this was the only reply generating on the queue, at both the moment it
 *                   was handed to a worker and the moment that worker finished with it. The speed
 *                   of a reply that had the deployment to itself is a different quantity from the
 *                   speed of one that shared it, and reporting the faster of the two as though
 *                   they were comparable is what this flag exists to prevent
 *                   ({@code GenerationRateWindowsAndLayout_260923_oo01})
 * @param ranAtFullSlots whether every attached slot was generating, at both of those moments
 */
public record GenerationMeasurement(String queueName, String address,
                                    long queuedMs, long firstMs, long decodeMs,
                                    long tokens, long characters,
                                    boolean ranAlone, boolean ranAtFullSlots) {

    /**
     * A measurement taken where the queue's occupancy was never reported. It counts in every sum
     * and in neither per-reply peak, which is what an unknown occupancy has to mean.
     */
    public GenerationMeasurement(String queueName, String address, long queuedMs, long firstMs,
                                 long decodeMs, long tokens, long characters) {
        this(queueName, address, queuedMs, firstMs, decodeMs, tokens, characters, false, false);
    }
}
