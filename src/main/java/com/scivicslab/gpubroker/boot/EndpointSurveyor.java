package com.scivicslab.gpubroker.boot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.EndpointProbe;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Probes every configured address for every known {@link EndpointProbe} kind and reports what
 * answered. The one place that turns {@code broker.nodes} into a list of live endpoints.
 *
 * <p>Two callers share one reading of it: {@link JobQueueRegistry} (which queues exist and which
 * addresses serve them) and {@code StatusHistoryRecorder} (which addresses were alive). They are
 * separate decisions — see {@code PeriodicRediscovery_260923_oo01} — but not separate probes:
 * running the survey twice a minute would double the traffic to every node and would make the
 * liveness denominator count probes nobody asked for.
 */
@Singleton
public class EndpointSurveyor {

    private static final Logger LOG = Logger.getLogger(EndpointSurveyor.class);

    /** One live endpoint, with the probe kind that recognised it (needed for its request path). */
    public record Found(EndpointProbe probe, EndpointInfo info) {}

    @Inject
    Instance<EndpointProbe> knownProbeBeans;

    @Inject
    BrokerConfig brokerConfig;

    /**
     * Every node IP to probe, with each configured CIDR block expanded to its addresses.
     * The single exit for the package-private {@code CidrRange}.
     */
    public List<String> expandNodeIps() {
        List<String> expanded = new ArrayList<>();
        for (String entry : brokerConfig.nodes().orElse(List.of())) {
            expanded.addAll(CidrRange.expand(entry));
        }
        return expanded;
    }

    /**
     * One round of probing. Returns a {@link Found} per address that answered; an address that
     * answers more than one kind appears once per kind, as the survey itself reports it.
     *
     * <p>Never throws: a round that fails must not stop the next one. A failed round returns
     * whatever did answer, which the callers treat as "these are alive" — the addresses missing
     * from it are recorded as down, and {@code JobQueueRegistry.reconcile} deliberately does
     * nothing about a missing address.
     */
    public List<Found> surveyNow() {
        List<EndpointProbe> knownProbes = knownProbeBeans.stream().toList();
        List<String> nodeIps = expandNodeIps();
        Map<String, BrokerConfig.EndpointCapability> capabilities = brokerConfig.capabilities();

        List<Found> found = new ArrayList<>();
        try (ExecutorService kindSurveys = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<EndpointInfo>>> surveys = new ArrayList<>();
            for (EndpointProbe probe : knownProbes) {
                surveys.add(kindSurveys.submit(() -> probe.survey(nodeIps, capabilities)));
            }
            // (each kind's survey already probes its own addresses in parallel)
            for (int i = 0; i < knownProbes.size(); i++) {
                for (EndpointInfo info : surveys.get(i).get()) {
                    found.add(new Found(knownProbes.get(i), info));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error("endpoint survey failed; continuing with whatever answered", e);
        }
        return found;
    }
}
