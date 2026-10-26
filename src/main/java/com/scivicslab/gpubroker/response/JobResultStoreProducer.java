package com.scivicslab.gpubroker.response;

import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The single place that wraps {@link JobResultStore} as an actor — the same role
 * {@code ActorSystemProducer} plays for the {@code ActorSystem} itself, and {@code
 * StatusHistoryStoreProducer} plays for {@code StatusHistoryStore}.
 *
 * <p>When {@code gpu.broker.jobs-dir} is set, the store keeps its jobs there and reads back
 * the ones a previous run left, so a restart does not lose them. Left unset, it is in-memory only.
 * (A {@code gpu.broker.*} key, not {@code broker.*}: keys under {@code broker.} must be declared in
 * {@code BrokerConfig}.)
 */
@Singleton
public class JobResultStoreProducer {

    private static final Logger LOG = Logger.getLogger(JobResultStoreProducer.class);

    @ConfigProperty(name = "gpu.broker.jobs-dir")
    Optional<String> jobsDir;

    @Produces
    @Singleton
    public ActorRef<JobResultStore> jobResultStore(ActorSystem system) {
        if (jobsDir.isEmpty() || jobsDir.get().isBlank()) {
            LOG.info("gpu.broker.jobs-dir is not set: submitted jobs are kept in memory only");
            return system.actorOf("job-results", new JobResultStore());
        }
        JobResultStore store = new JobResultStore(new JobJournal(Path.of(jobsDir.get())));
        LOG.infof("jobs are kept in %s: %d waiting to run again", jobsDir.get(), store.recoverable().size());
        return system.actorOf("job-results", store);
    }
}
