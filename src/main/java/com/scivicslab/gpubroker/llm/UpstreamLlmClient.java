package com.scivicslab.gpubroker.llm;

import com.scivicslab.gpubroker.model.Job;

/**
 * Sends a job to one upstream vLLM node and returns once that node is done.
 *
 * <p>{@link #send} blocks the calling {@code NodeActor}'s virtual thread until the
 * remote vLLM response completes. Because the broker performs no CPU-bound work,
 * blocking the actor's own virtual thread on this I/O wait is intentional and
 * gives N=1 per node for free (see {@code CompletionDrivenDispatch}).
 *
 * <p>This is an interface so the dispatch logic can be unit-tested with a stub
 * upstream that blocks deterministically, without touching a real vLLM. The HTTP
 * implementation is added in the S_proxy transition.
 */
public interface UpstreamLlmClient {

    /**
     * Send {@code job} to the vLLM at {@code url} and block until it completes.
     *
     * @throws UpstreamException if the node fails mid-flight
     */
    void send(String url, Job job) throws UpstreamException;
}
