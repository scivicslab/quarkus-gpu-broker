package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** vLLM's OpenAI-compatible chat completions endpoint. */
public final class VllmChatEndpoint extends AiServiceEndpoint {

    public VllmChatEndpoint(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/v1/chat/completions";
    }
}
