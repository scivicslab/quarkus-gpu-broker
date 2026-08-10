package com.scivicslab.gpubroker.model;

/**
 * Where relayed upstream chunks are written on their way back to the client.
 *
 * <p>The {@code NodeActor} relay calls {@link #write} for each chunk it reads
 * from the upstream vLLM. The proxy front (S_proxy) supplies a sink that feeds
 * the HTTP response; unit tests supply a recording sink.
 */
public interface ResponseSink {

    /** Forward one upstream chunk toward the client. */
    void write(String chunk);
}
