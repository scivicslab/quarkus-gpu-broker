package com.scivicslab.gpubroker.boot;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.scivicslab.gpubroker.history.StatusHistoryRecorder;

import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Probes every node once a minute and hands the one reading to its two consumers, in order:
 * {@link JobQueueRegistry#reconcile} first, so that a node whose model was replaced has already
 * moved to its new queue, then {@link StatusHistoryRecorder#record}, which writes the liveness
 * row for every address that answered plus every address still registered afterwards.
 *
 * <p>Before this class existed, the survey ran inside {@code StatusHistoryRecorder} and its
 * result was only ever looked at — a node that started serving a different model stayed
 * mis-registered until the broker was restarted. See {@code PeriodicRediscovery_260923_oo01}.
 */
@Singleton
public class EndpointPoller {

    private static final Logger LOG = Logger.getLogger(EndpointPoller.class.getName());

    @Inject
    EndpointSurveyor surveyor;

    @Inject
    JobQueueRegistry registry;

    @Inject
    StatusHistoryRecorder recorder;

    // delayed: startup has just probed every node itself, and the first round would
    // otherwise land in the same second as JobQueueRegistry's own survey.
    @Scheduled(every = "1m", delayed = "1m")
    void poll() {
        try {
            List<EndpointSurveyor.Found> found = surveyor.surveyNow();
            registry.reconcile(found);
            recorder.record(found);
        } catch (RuntimeException e) {
            // A scheduled method that throws is retried but never recovers on its own;
            // one failed round must not stop later rounds.
            LOG.log(Level.SEVERE, "endpoint poll failed", e);
        }
    }
}
