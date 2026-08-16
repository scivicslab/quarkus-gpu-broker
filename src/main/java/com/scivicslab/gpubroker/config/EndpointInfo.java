package com.scivicslab.gpubroker.config;

/**
 * One physical endpoint {@link EndpointProbe#survey} found: where it is,
 * which {@code JobQueue} it belongs to, and how many concurrency slots
 * {@code AiServiceEndpointBuilder} should give it. Carries only what {@code
 * AiServiceEndpointBuilder} needs to construct an {@code AiServiceEndpoint} —
 * display-only capability fields (context length, etc.) live in {@link
 * BrokerConfig.EndpointCapability} and are read directly by the status page,
 * never through here (see {@code CapabilityConfig_260810_oo01} "なぜ表示専用の能力は
 * EndpointInfo を経由しないか").
 */
public record EndpointInfo(String address, String queueName, int maxConcurrency) {
}
