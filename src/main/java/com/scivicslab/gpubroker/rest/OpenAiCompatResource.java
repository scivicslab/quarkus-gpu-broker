package com.scivicslab.gpubroker.rest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.config.ChatQueueName;

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

    @Inject
    BrokerConfig config;

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
        String queueName = ChatQueueName.of(model);
        // Refused here, before it is queued, so the client gets a real 400 naming the cause; sent
        // on, the inference server's own 400 would reach the client as a 200 carrying an error
        // body (ImageInputTable_261009_oo01).
        if (refusesImage(config.imageInput(), queueName, rawBody)) {
            return errorResponse(400, "model " + model + " does not accept image input");
        }
        return proxy.submit(queueName, rawBody, contentType, priorityHeader);
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
     * routes to the right queue ({@link com.scivicslab.gpubroker.config.ChatQueueName#of}
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
        Map<String, Integer> contextLengths = queues.contextLengths();
        for (var entry : queues.displayNames().entrySet()) {
            if (!entry.getKey().startsWith(ChatQueueName.PREFIX)) {
                continue;
            }
            ObjectNode model = data.addObject();
            model.put("id", entry.getValue());
            model.put("object", "model");
            model.put("owned_by", "gpu-broker");
            // The field vLLM itself answers here, so a client that used to talk to a vLLM node
            // directly reads the window the same way through the broker. Absent when neither the
            // service nor an operator said what it is, which a client must read as "unknown"
            // rather than as zero.
            Integer contextLength = contextLengths.get(entry.getKey());
            if (contextLength != null && contextLength > 0) {
                model.put("max_model_len", contextLength);
            }
            // The shape Strata answers in its own GET /v1/models. Absent for a queue the table
            // does not list, which a client must read as "unknown" (ImageInputTable_261009_oo01).
            Boolean acceptsImages = config.imageInput().get(entry.getKey());
            if (acceptsImages != null) {
                ArrayNode modalities = model.putObject("architecture").putArray("input_modalities");
                modalities.add("text");
                if (acceptsImages) {
                    modalities.add("image");
                }
            }
        }
        return Response.ok(root.toString(), MediaType.APPLICATION_JSON).build();
    }

    /** Whether the table says {@code queueName} takes no images and the request carries one. */
    static boolean refusesImage(Map<String, Boolean> imageInput, String queueName, byte[] rawBody) {
        return Boolean.FALSE.equals(imageInput.get(queueName)) && carriesImage(rawBody);
    }

    /** Whether any message's content holds a part of type {@code image_url}. */
    static boolean carriesImage(byte[] rawBody) {
        try {
            for (JsonNode message : MAPPER.readTree(rawBody).path("messages")) {
                for (JsonNode part : message.path("content")) {
                    if ("image_url".equals(part.path("type").asText())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
