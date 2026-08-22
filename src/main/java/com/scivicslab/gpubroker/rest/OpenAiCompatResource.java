package com.scivicslab.gpubroker.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.config.VllmQueueName;

import io.smallrye.mutiny.Multi;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.RestMulti;

/**
 * OpenAI-compatible facade over the queue-based API ({@link ProxyResource}), so a third-party
 * OpenAI-API client (e.g. OpenWebUI) can point its base URL directly at this broker instead of one
 * fixed vLLM/embedding node. See {@code OpenAiCompatFacade_260822_oo01}.
 *
 * <p>Every method here resolves a {@code queueName} and delegates the actual streaming submission
 * to {@link ProxyResource#submit} -- this class adds no submission logic of its own, only the
 * OpenAI URL shape and {@code queueName} resolution on top of it.
 */
@Path("/v1")
public class OpenAiCompatResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMBEDDING_QUEUE = "embedding-e5large";

    @Inject
    ProxyResource proxy;

    @Inject
    JobQueueRegistry queues;

    /** {@code model} is read from the request body (the same field vLLM itself reads), not a path param. */
    @POST
    @Path("/chat/completions")
    public RestMulti<Buffer> chatCompletions(byte[] rawBody,
                                              @HeaderParam("Content-Type") String contentType,
                                              @HeaderParam("X-Job-Priority") @DefaultValue("foreground") String priorityHeader) {
        String model = extractModel(rawBody);
        if (model == null || model.isBlank()) {
            return errorResponse(400, "request body must include a \"model\" field");
        }
        return proxy.submit(VllmQueueName.of(model), rawBody, contentType, priorityHeader);
    }

    /** The embedding model is effectively singular across the broker, so no {@code model}-based routing is needed. */
    @POST
    @Path("/embeddings")
    public RestMulti<Buffer> embeddings(byte[] rawBody,
                                         @HeaderParam("Content-Type") String contentType,
                                         @HeaderParam("X-Job-Priority") @DefaultValue("foreground") String priorityHeader) {
        return proxy.submit(EMBEDDING_QUEUE, rawBody, contentType, priorityHeader);
    }

    /**
     * Lists currently discovered vLLM models. The {@code id} advertised here is the true,
     * unsanitized model id (e.g. {@code google/gemma-4-26B-A4B-it}) the downstream vLLM server
     * itself expects in a chat request's {@code "model"} field -- not the sanitized queue-name
     * suffix. A client echoing this id straight back in {@code POST /v1/chat/completions} both
     * routes to the right queue ({@link com.scivicslab.gpubroker.config.VllmQueueName#of}
     * re-derives the same queue name) and reaches the downstream server with a {@code "model"}
     * value it recognizes -- see {@code OpenAiCompatFacade_260822_oo01} "なぜ表示名にサニタイズ前の
     * モデルIDが要るか".
     */
    @GET
    @Path("/models")
    public Response models() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("object", "list");
        ArrayNode data = root.putArray("data");
        for (var entry : queues.displayNames().entrySet()) {
            if (!entry.getKey().startsWith("vllm-")) {
                continue;
            }
            ObjectNode model = data.addObject();
            model.put("id", entry.getValue());
            model.put("object", "model");
            model.put("owned_by", "gpu-broker");
        }
        return Response.ok(root.toString(), MediaType.APPLICATION_JSON).build();
    }

    static String extractModel(byte[] rawBody) {
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            JsonNode model = root.path("model");
            return model.isMissingNode() ? null : model.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static RestMulti<Buffer> errorResponse(int status, String message) {
        String body = "{\"error\":{\"message\":\"" + message.replace("\"", "'") + "\"}}";
        return RestMulti.fromMultiData(Multi.createFrom().item(Buffer.buffer(body)))
                .status(status)
                .build();
    }
}
