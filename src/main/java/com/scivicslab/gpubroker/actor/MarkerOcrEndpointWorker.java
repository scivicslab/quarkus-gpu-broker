package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** Marker's OCR endpoint. */
public final class MarkerOcrEndpointWorker extends AiServiceEndpointWorker {

    public MarkerOcrEndpointWorker(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/marker/upload";
    }
}
