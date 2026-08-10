package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.actor.AiServiceEndpointWorker;
import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.gpubroker.response.JobResultStore;
import com.scivicslab.gpubroker.response.PolledResponseSink;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/**
 * The non-streaming, submit-then-poll receiving end (the {@code
 * YOMITOKU_OCR}/{@code MARKER_OCR}/{@code EMBEDDING} kinds' path): {@code
 * POST} returns a {@code jobId} immediately without waiting for the {@code
 * AiServiceEndpoint}; {@code GET} returns the current status, and the
 * result once it is {@code DONE}.
 *
 * <p>{@code @Blocking} because {@link #submit} calls {@code join()} on the
 * queue ask — safe on a worker thread, not on the I/O thread.
 */
@Path("/jobs/{queueName}")
public class AsyncJobResource {

    @Inject
    JobQueueRegistry queues;

    @Inject
    ActorSystem system;

    @Inject
    JobResultStore results;

    @POST
    @Blocking
    public Response submit(@PathParam("queueName") String queueName, byte[] rawBody,
                            @HeaderParam("Content-Type") String contentType,
                            @HeaderParam("X-Job-Priority") @DefaultValue("background") String priorityHeader) {
        if (queues.isDraining()) {
            return Response.status(503).build();
        }

        ActorRef<JobQueue> queue = queues.get(queueName);
        if (queue == null) {
            return Response.status(404).build();
        }

        String jobId = results.register();
        Priority priority = Priority.fromHeader(priorityHeader);
        Job job = Job.first(new RequestBody(rawBody, contentType), priority, new PolledResponseSink(jobId, results));

        String endpointId = queue.ask(q -> q.submit(job)).join();
        if (endpointId != null) {
            dispatch(endpointId, job);
        }
        return Response.accepted(jobId).build();
    }

    @GET
    @Path("/{jobId}")
    public Response fetch(@PathParam("jobId") String jobId) {
        return results.get(jobId)
                .map(r -> Response.ok(r).build())
                .orElse(Response.status(404).build());
    }

    private void dispatch(String endpointId, Job job) {
        ActorRef<AiServiceEndpointWorker> worker = system.getActor(endpointId);
        worker.tell(w -> w.assign(job));
    }
}
