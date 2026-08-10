package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** YomiToku's OCR endpoint. */
public final class YomiTokuOcrEndpointWorker extends AiServiceEndpointWorker {

    public YomiTokuOcrEndpointWorker(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/ocr";
    }
}
