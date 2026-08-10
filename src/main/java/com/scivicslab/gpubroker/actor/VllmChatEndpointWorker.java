package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** vLLM's OpenAI-compatible chat completions endpoint. */
public final class VllmChatEndpointWorker extends AiServiceEndpointWorker {

    public VllmChatEndpointWorker(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/v1/chat/completions";
    }
}
