package com.scivicslab.gpubroker.actor;

import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.llm.AiServiceClient;
import com.scivicslab.gpubroker.llm.HttpAiServiceClient;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Turns an {@link EndpointInfo} (the facts {@code EndpointProbe.survey}
 * collected) into an actual {@link AiServiceEndpoint} POJO, ready for {@code
 * JobQueueRegistry} to {@code createChild} into the Actor tree.
 *
 * <p>A single concrete class, not one per {@code EndpointProbe} kind —
 * {@code AiServiceEndpoint} and {@code AiServiceEndpointWorker} are each
 * already a single concrete class, and the only thing that varies by kind
 * ({@code requestPath}) is supplied as a plain argument. See {@code
 * AiServiceEndpointSubclasses_260810_oo01} "なぜ Builder は種類ごとではなく単一のクラスで
 * 足りるか".
 *
 * <p>Deliberately separate from {@code EndpointProbe}: gathering facts
 * (HTTP probing, config reads) and constructing Java objects/actor graphs
 * are different kinds of work — see {@code CapabilityConfig_260810_oo01}
 * "なぜ事実収集（EndpointProbe）とオブジェクト組み立て（Builder）を分けるか".
 */
@ApplicationScoped
public class AiServiceEndpointBuilder {

    /**
     * @param info        the facts {@code EndpointProbe.survey} collected for this instance
     * @param requestPath the URL path real job requests go to, from the {@code EndpointProbe} that found it
     * @return a fully-constructed {@code AiServiceEndpoint}, not yet bound or started
     */
    public AiServiceEndpoint build(EndpointInfo info, String requestPath) {
        AiServiceClient client = new HttpAiServiceClient();
        return new AiServiceEndpoint(
                info.address(),
                info.maxConcurrency(),
                () -> new AiServiceEndpointWorker(info.queueName(), info.address(), client, requestPath));
    }
}
