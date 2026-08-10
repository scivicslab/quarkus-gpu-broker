package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.llm.AiServiceClient;

/** YomiToku's OCR endpoint. */
public final class YomiTokuOcrEndpoint extends AiServiceEndpoint {

    public YomiTokuOcrEndpoint(String queueName, String address, AiServiceClient client) {
        super(queueName, address, client);
    }

    @Override
    protected String requestPath() {
        return "/ocr";
    }
}
