package com.scivicslab.gpubroker.boot;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The single place that wraps {@link JobQueueRegistryState} as an actor — the same role
 * {@code ActorSystemProducer} plays for the {@code ActorSystem} itself.
 */
@Singleton
public class JobQueueRegistryStateProducer {

    @Produces
    @Singleton
    public ActorRef<JobQueueRegistryState> jobQueueRegistryState(ActorSystem system) {
        return system.actorOf("job-queue-registry-state", new JobQueueRegistryState());
    }
}
