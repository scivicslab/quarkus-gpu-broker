package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** The embedding server's endpoint. */
public final class EmbeddingEndpointWorker extends AiServiceEndpointWorker {

    public EmbeddingEndpointWorker(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/v1/embeddings";
    }
}
