package com.scivicslab.gpubroker.rest;

import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.gpubroker.response.JobJournal;
import com.scivicslab.gpubroker.response.JobResultStore;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Runs again the jobs a previous broker run accepted but did not finish.
 *
 * <p>After a restart the {@code JobResultStore} lists them ({@code recoverable}), but their queues
 * exist only once the endpoint survey has found the nodes again, so this looks every few seconds and
 * puts each job back on its queue as soon as that queue is there. A job whose submitter is at its
 * admission limit waits for the next look.
 */
@Singleton
public class JobRecovery {

    private static final Logger LOG = Logger.getLogger(JobRecovery.class);

    @Inject
    ActorRef<JobResultStore> results;

    @Inject
    JobQueueRegistry queues;

    @Inject
    SubmissionAdmissionControl admission;

    @Inject
    AsyncJobDispatcher dispatcher;

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void recover() {
        if (queues.isDraining()) {
            return;
        }
        for (JobJournal.Accepted job : results.ask(JobResultStore::recoverable).join()) {
            if (queues.get(job.queueName()) == null || !admission.tryAdmit(job.submitterId(), job.queueName())) {
                continue;
            }
            if (dispatcher.dispatch(job.jobId(), job.queueName(), job.priority(), job.submitterId(),
                    new RequestBody(job.body(), job.contentType()))) {
                results.tell(r -> r.resubmitted(job.jobId()));
                LOG.infof("ran job %s again on %s after a restart", job.jobId(), job.queueName());
            } else {
                admission.release(job.submitterId(), job.queueName());
            }
        }
    }
}
