package com.scivicslab.gpubroker.response;

import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The CDI-scheduled trigger for {@link JobResultStore#sweep}. {@code @Scheduled} needs a CDI
 * bean method to call; the sweep logic itself stays inside the actor, reached the same way every
 * other caller reaches it ({@code tell}), so this class holds no state of its own.
 */
@Singleton
public class JobResultSweepScheduler {

    @Inject
    ActorRef<JobResultStore> results;

    @Scheduled(every = "10m")
    void sweep() {
        results.tell(JobResultStore::sweep);
    }
}
