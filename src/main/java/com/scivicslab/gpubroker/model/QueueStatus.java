package com.scivicslab.gpubroker.model;

/**
 * A {@link QueueSnapshot} paired with the {@code queueName} it belongs to.
 * {@code JobQueue} itself does not know its own name (same reasoning as
 * {@code AiServiceEndpoint} not holding an {@code ActorRef<JobQueue>}
 * field) — only {@code JobQueueRegistry} knows both.
 */
public record QueueStatus(String queueName, QueueSnapshot snapshot) {
}
