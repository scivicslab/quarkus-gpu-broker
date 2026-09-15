package com.scivicslab.gpubroker.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * The client's request body with this queue's generation limits filled in
 * ({@code RunawayGenerationLimits_260915_oo01}).
 *
 * <p>The broker forwards a request body verbatim everywhere else, and did here too until one reply
 * that named no {@code max_tokens} ran for thirty minutes. A cap cannot be left to the clients:
 * they are several programs written by several people, and only this one point is common to all of
 * them.</p>
 *
 * <p>Only a chat request is touched -- one that carries {@code messages}. Embedding requests reach
 * a queue through this same method and carry {@code input} instead; {@code max_tokens} means
 * nothing there, and a server is entitled to refuse a field it does not know. Anything that is not
 * a JSON object at all is returned untouched too, which covers the OCR paths' multipart bodies.</p>
 */
public final class LimitedRequestBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LimitedRequestBody() {
    }

    /**
     * @param body  what the client sent
     * @param limit this queue's limits, or {@code null} when it has none
     * @return the body to forward; {@code body} itself when nothing had to change
     */
    public static RequestBody of(RequestBody body, BrokerConfig.QueueGenerationLimit limit) {
        if (limit == null) {
            return body;
        }
        if (limit.maxTokens().isEmpty() && limit.repetitionPenalty().isEmpty()
                && limit.frequencyPenalty().isEmpty()) {
            return body;
        }
        try {
            JsonNode root = MAPPER.readTree(body.bytes());
            if (root == null || !root.isObject()) {
                return body;
            }
            ObjectNode object = (ObjectNode) root;
            if (!object.has("messages")) {
                return body;
            }
            boolean changed = capTokens(object, limit);
            changed |= fillIfAbsent(object, "repetition_penalty", limit.repetitionPenalty());
            changed |= fillIfAbsent(object, "frequency_penalty", limit.frequencyPenalty());
            return changed ? new RequestBody(MAPPER.writeValueAsBytes(object), body.contentType()) : body;
        } catch (Exception e) {
            // Not JSON this can read. Forwarding it unchanged is what the broker did before there
            // were limits at all, and is better than refusing a request over a cap.
            return body;
        }
    }

    /**
     * A client that named a smaller cap keeps it: the limit is a ceiling, not a target. A client
     * that named none, or a larger one, is brought down to the ceiling.
     */
    private static boolean capTokens(ObjectNode object, BrokerConfig.QueueGenerationLimit limit) {
        if (limit.maxTokens().isEmpty()) {
            return false;
        }
        int ceiling = limit.maxTokens().getAsInt();
        JsonNode asked = object.get("max_tokens");
        if (asked != null && asked.isNumber() && asked.asInt() > 0 && asked.asInt() <= ceiling) {
            return false;
        }
        object.put("max_tokens", ceiling);
        return true;
    }

    private static boolean fillIfAbsent(ObjectNode object, String field, java.util.OptionalDouble value) {
        if (value.isEmpty() || (object.has(field) && !object.get(field).isNull())) {
            return false;
        }
        object.put(field, value.getAsDouble());
        return true;
    }
}
