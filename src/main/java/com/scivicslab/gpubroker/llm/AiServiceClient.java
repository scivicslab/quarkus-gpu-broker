package com.scivicslab.gpubroker.llm;

import com.scivicslab.gpubroker.model.Job;

/**
 * Sends a job's request to one real AI service (at {@code address + path})
 * and relays its response into {@code job.responseSink()}, returning once
 * that service is done.
 *
 * <p>{@link #send} blocks the calling {@code AiServiceEndpoint}'s virtual
 * thread until the response completes — the broker does no CPU-bound work,
 * so blocking the actor's own virtual thread on this I/O wait is
 * intentional and gives N=1 per endpoint for free.
 *
 * <p>An interface so dispatch logic can be unit-tested with a deterministic
 * stub, without a real AI service.
 */
public interface AiServiceClient {

    /**
     * Send {@code job.request()} to {@code address + path} and block until
     * it completes, calling {@code job.responseSink()}'s {@code start},
     * {@code emit} and {@code complete} along the way.
     *
     * @throws AiServiceCallException if the AI service fails mid-flight
     */
    void send(String address, String path, Job job) throws AiServiceCallException;
}
