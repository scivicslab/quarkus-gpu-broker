package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.boot.JobQueueRegistry;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Read-only snapshot of every discovered queue's current state — the only
 * page in {@code gpu-broker} meant to be opened in a browser. Served at the
 * root path: nothing else claims {@code /}, and a separate {@code /status}
 * path would exist for no reason.
 *
 * <p>{@code @Blocking} for the same reason as {@code AsyncJobResource}:
 * {@link JobQueueRegistry#statusSnapshot()} calls {@code ask(...).join()}
 * per queue, which must not run on the I/O thread.
 */
@Path("/")
public class StatusResource {

    @Inject
    JobQueueRegistry queues;

    @GET
    @Blocking
    @Produces(MediaType.TEXT_HTML)
    public String status() {
        return StatusPageRenderer.render(queues.statusSnapshot());
    }
}
