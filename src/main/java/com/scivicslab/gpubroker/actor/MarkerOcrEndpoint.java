package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** Marker's OCR endpoint. */
public final class MarkerOcrEndpoint extends AiServiceEndpoint {

    public MarkerOcrEndpoint(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/convert";
    }
}
