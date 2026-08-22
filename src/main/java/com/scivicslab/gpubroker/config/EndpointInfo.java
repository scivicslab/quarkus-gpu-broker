package com.scivicslab.gpubroker.config;

/**
 * One physical endpoint {@link EndpointProbe#survey} found: where it is,
 * which {@code JobQueue} it belongs to, and how many concurrency slots
 * {@code AiServiceEndpointBuilder} should give it. Carries only what {@code
 * AiServiceEndpointBuilder} needs to construct an {@code AiServiceEndpoint} —
 * display-only capability fields (context length, etc.) live in {@link
 * BrokerConfig.EndpointCapability} and are read directly by the status page,
 * never through here (see {@code CapabilityConfig_260810_oo01} "なぜ表示専用の能力は
 * EndpointInfo を経由しないか"). {@code displayName} is the exception: {@code
 * JobQueueRegistry} needs it (from {@link EndpointProbe#deriveDisplayName}) to serve {@code
 * OpenAiCompatResource}'s {@code GET /v1/models}, and it is only known at discovery time here,
 * not reconstructible later from the sanitized {@code queueName} — see {@code
 * OpenAiCompatFacade_260822_oo01}.
 */
public record EndpointInfo(String address, String queueName, String displayName, int maxConcurrency) {
}
