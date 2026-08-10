package com.scivicslab.gpubroker.boot;

/**
 * Used to fail a {@code ResponseSink} whose job was still waiting,
 * undispatched, when {@code JobQueueRegistry} started a graceful drain.
 */
public class DrainingException extends RuntimeException {

    public DrainingException() {
        super("broker is draining for a rolling update; retry against the new process");
    }
}
