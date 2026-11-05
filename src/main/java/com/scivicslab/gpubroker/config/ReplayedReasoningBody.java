package com.scivicslab.gpubroker.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scivicslab.gpubroker.model.RequestBody;

/**
 * The client's chat request with each replayed reasoning carried under one name only.
 *
 * <p>A client that sends the model's earlier turns back may put the reasoning of an assistant turn
 * under both {@code reasoning} and {@code reasoning_content}: Hermes Agent does, to suit servers
 * that read either. vLLM reads the two as one field and refuses such a message with 400
 * ("duplicate field `reasoning`"), so every agent turn after a tool call failed. The broker keeps
 * {@code reasoning}, the name vLLM answers with, and drops {@code reasoning_content} from a
 * message that has both.</p>
 *
 * <p>A body that is not a JSON object with {@code messages}, or in which no message has both
 * names, is returned untouched.</p>
 */
public final class ReplayedReasoningBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplayedReasoningBody() {
    }

    /**
     * @param body what the client sent
     * @return the body to forward; {@code body} itself when nothing had to change
     */
    public static RequestBody of(RequestBody body) {
        try {
            JsonNode root = MAPPER.readTree(body.bytes());
            if (root == null || !root.isObject() || !root.path("messages").isArray()) {
                return body;
            }
            boolean changed = false;
            for (JsonNode message : root.path("messages")) {
                if (message.isObject() && message.has("reasoning") && message.has("reasoning_content")) {
                    ((ObjectNode) message).remove("reasoning_content");
                    changed = true;
                }
            }
            return changed ? new RequestBody(MAPPER.writeValueAsBytes(root), body.contentType()) : body;
        } catch (Exception e) {
            // Not JSON this can read (the OCR paths' multipart bodies): forwarded as it came.
            return body;
        }
    }
}
