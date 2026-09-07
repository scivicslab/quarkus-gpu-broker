package com.scivicslab.gpubroker.response;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The single place that wraps {@link JobResultStore} as an actor — the same role
 * {@code ActorSystemProducer} plays for the {@code ActorSystem} itself, and {@code
 * StatusHistoryStoreProducer} plays for {@code StatusHistoryStore}.
 */
@Singleton
public class JobResultStoreProducer {

    @Produces
    @Singleton
    public ActorRef<JobResultStore> jobResultStore(ActorSystem system) {
        return system.actorOf("job-results", new JobResultStore());
    }
}
