package com.scivicslab.gpubroker.rest;

import com.scivicslab.gpubroker.actor.AiServiceEndpointWorker;
import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.boot.JobQueueRegistry;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.RequestBody;
import com.scivicslab.gpubroker.response.JobResultStore;
import com.scivicslab.gpubroker.response.PolledResponseSink;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Puts an already-registered submit-then-poll job on its queue. Shared by {@link AsyncJobResource}
 * (a job that has just arrived) and {@link JobRecovery} (a job read back from disk after a restart):
 * both end the same way, with the result written to the {@code JobResultStore} under the same
 * {@code jobId} and the submitter's admission slot released.
 */
@Singleton
public class AsyncJobDispatcher {

    @Inject
    JobQueueRegistry queues;

    @Inject
    ActorSystem system;

    @Inject
    ActorRef<JobResultStore> results;

    @Inject
    SubmissionAdmissionControl admission;

    /**
     * @return false if {@code queueName} does not exist (yet); the caller keeps the job and its admission slot
     */
    public boolean dispatch(String jobId, String queueName, Priority priority, String submitterId,
                            RequestBody request) {
        ActorRef<JobQueue> queue = queues.get(queueName);
        if (queue == null) {
            return false;
        }
        PolledResponseSink sink = new PolledResponseSink(jobId, results);
        Job job = Job.first(request, priority,
                new AdmissionReleasingResponseSink(sink, () -> admission.release(submitterId, queueName)));
        String endpointId = queue.ask(q -> q.submit(job)).join();
        if (endpointId != null) {
            ActorRef<AiServiceEndpointWorker> worker = system.getActor(endpointId);
            worker.tell(w -> w.assign(job));
        }
        return true;
    }
}
