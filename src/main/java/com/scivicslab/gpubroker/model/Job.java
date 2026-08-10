package com.scivicslab.gpubroker.model;

/**
 * A single request flowing through the broker, from whichever GPU broker
 * client submitted it, through the {@code JobQueue}, to whichever
 * {@code AiServiceEndpoint} ends up handling it — and, on failure, to
 * however many more endpoints {@code requeue} tries next.
 *
 * <p>Every capability (vLLM chat, OCR, embedding) is carried by this same
 * type: the broker never interprets {@link #request}'s bytes, so there is
 * nothing capability-specific for a separate {@code Job} type to hold. It is
 * a {@code record} because it is handed across several actors' virtual
 * threads in turn and none of them ever mutate it — {@link #nextAttempt()}
 * builds a new instance rather than changing this one.
 */
public record Job(RequestBody request, Priority priority, ResponseSink responseSink, int attempt) {

    public Job {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
    }

    /** The first attempt at a newly submitted job. */
    public static Job first(RequestBody request, Priority priority, ResponseSink responseSink) {
        return new Job(request, priority, responseSink, 0);
    }

    /** The same job, carried by the same {@link #responseSink}, one attempt later. */
    public Job nextAttempt() {
        return new Job(request, priority, responseSink, attempt + 1);
    }
}
