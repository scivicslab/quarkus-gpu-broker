package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.boot.GatewayActors;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

/**
 * OpenAI-compatible front for the broker (transition S_health -&gt; S_proxy),
 * with live passthrough.
 *
 * <p>Accepts {@code POST /v1/chat/completions}, reads the FG/BG priority from the
 * {@code X-Llm-Priority} header (default foreground), and submits a {@link Job}
 * to the {@code QueueActor}. It then waits only until a node has the upstream
 * response headers — to mirror the upstream {@code Content-Type} onto the client
 * response — and streams the body through a {@link StreamingResponseBridge} as it
 * arrives. The relay writes chunks to the bridge from the node's thread; this
 * request thread alone writes them to the client output stream.
 *
 * <p>The body is forwarded verbatim to keep the proxy OpenAI-compatible and
 * language-agnostic. Both {@code application/json} (non-stream) and {@code
 * text/event-stream} (stream) responses pass through unchanged, because the
 * response Content-Type is taken from the upstream, not fixed here.
 */
@Path("/v1/chat/completions")
public class ChatCompletionsResource {

    @Inject
    GatewayActors actors;

    @POST
    @Blocking
    @Consumes(MediaType.APPLICATION_JSON)
    public Response complete(String body,
                             @HeaderParam("X-Llm-Priority") @DefaultValue("foreground") String priorityHeader) {
        Priority priority = Priority.fromHeader(priorityHeader);

        StreamingResponseBridge bridge = new StreamingResponseBridge();
        Job job = Job.request(body, bridge, priority);
        job.completion().whenComplete((v, t) -> bridge.finish());   // end stream when relay completes

        actors.queue().tell(q -> q.submit(job));

        String contentType = job.awaitContentType();                // park until a node connects upstream
        StreamingOutput out = bridge::writeTo;
        return Response.ok(out).type(contentType).build();
    }
}
