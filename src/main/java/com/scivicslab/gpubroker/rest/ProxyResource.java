package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.actor.AiServiceEndpoint;
import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.boot.DrainingException;
import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.gpubroker.model.ResponseSink;
import com.scivicslab.gpubroker.response.StreamStart;
import com.scivicslab.gpubroker.response.StreamingResponseSink;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.jboss.resteasy.reactive.RestMulti;

/**
 * The synchronous-streaming receiving end (the {@code VLLM_CHAT} kind's
 * path): accepts a request, submits it, and returns a streaming response
 * whose headers reflect whatever {@code Content-Type} the {@code
 * AiServiceEndpoint} actually responds with.
 *
 * <p>{@code Content-Type} is not known until an {@code AiServiceEndpoint}
 * connects, so the response is built in two stages via {@code
 * RestMulti.fromUniResponse}: a {@link Uni}{@code <StreamStart>} that
 * resolves once {@code ResponseSink.start} is called, and the body {@link
 * Multi} it carries (already live and possibly buffering before that point).
 */
@Path("/queue/{queueName}")
public class ProxyResource {

    @Inject
    JobQueueRegistry queues;

    @Inject
    ActorSystem system;

    @POST
    public RestMulti<Buffer> submit(@PathParam("queueName") String queueName, byte[] rawBody,
                                     @HeaderParam("Content-Type") String contentType,
                                     @HeaderParam("X-Job-Priority") @DefaultValue("foreground") String priorityHeader) {
        if (queues.isDraining()) {
            return RestMulti.fromMultiData(Multi.createFrom().<Buffer>failure(new DrainingException()))
                    .status(503)
                    .build();
        }

        ActorRef<JobQueue> queue = queues.get(queueName);
        if (queue == null) {
            return RestMulti.fromMultiData(Multi.createFrom().<Buffer>failure(new NotFoundException()))
                    .status(404)
                    .build();
        }

        Priority priority = Priority.fromHeader(priorityHeader);
        Uni<StreamStart> started = Uni.createFrom().emitter(emitter -> {
            ResponseSink sink = new StreamingResponseSink(emitter);
            Job job = Job.first(new RequestBody(rawBody, contentType), priority, sink);
            // Non-blocking: this callback may run on the I/O thread, so we chain
            // onto the CompletableFuture instead of calling join() on it.
            queue.ask(q -> q.submit(job)).thenAccept(endpointId -> {
                if (endpointId != null) {
                    dispatch(endpointId, job);
                }
            });
        });

        return RestMulti.fromUniResponse(started, StreamStart::data, s -> headersOf(s.contentType()), s -> 200);
    }

    private MultivaluedMap<String, String> headersOf(String contentType) {
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        headers.add("Content-Type", contentType);
        return headers;
    }

    private void dispatch(String endpointId, Job job) {
        ActorRef<AiServiceEndpoint> endpoint = system.getActor(endpointId);
        endpoint.tell(w -> w.assign(job));
    }
}
