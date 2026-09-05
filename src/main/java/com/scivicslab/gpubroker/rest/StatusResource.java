package com.scivicslab.gpubroker.rest;

import java.util.List;

import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.history.StatusHistoryStore;

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

    @Inject
    BrokerConfig brokerConfig;

    @Inject
    StatusHistoryStore history;

    @GET
    @Blocking
    @Produces(MediaType.TEXT_HTML)
    public String status() {
        return StatusPageRenderer.render(queues.statusSnapshot(), brokerConfig.capabilities(), history);
    }

    /**
     * The same state as {@link #status}, for callers that are programs rather than people.
     *
     * <p>A separate path rather than content negotiation on {@code /}: a caller polling this is
     * doing something different from a person refreshing a page, and the two want different
     * caching and different failure handling. {@code QueueSnapshotStatus_260810_oo01} left this
     * open until a program needed it; html-saurus and quarkus-exdb2 now do, to decide whether
     * their embedding and transcription work can run.
     *
     * <p>{@code GET /v1/models} does not answer that question. It lists chat models only, and a
     * {@code 200} from it means the broker is up, not that any particular queue has an endpoint
     * answering.
     */
    @GET
    @Path("queues")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public List<QueueReport> queues() {
        return queues.statusSnapshot().stream()
                .map(status -> QueueReport.of(status, history))
                .toList();
    }
}
