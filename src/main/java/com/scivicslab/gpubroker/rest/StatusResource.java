package com.scivicslab.gpubroker.rest;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.history.StatusHistoryStore;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
     *
     * @param since ISO-8601 instant (e.g. {@code 2026-09-08T01:30:00Z}); when given, each
     *              endpoint reports every probe window at or after it instead of only the most
     *              recent one — for telling "was actually unreachable" apart from "a caller gave
     *              up waiting" after the fact, which the live snapshot alone cannot do once the
     *              moment has passed
     */
    @GET
    @Path("queues")
    @Blocking
    @Produces(MediaType.APPLICATION_JSON)
    public List<QueueReport> queues(@QueryParam("since") String since) {
        Instant sinceInstant = parseSince(since);
        return queues.statusSnapshot().stream()
                .map(status -> QueueReport.of(status, history, sinceInstant))
                .toList();
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("since must be an ISO-8601 instant, e.g. 2026-09-08T01:30:00Z: " + since);
        }
    }
}
