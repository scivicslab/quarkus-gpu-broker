package com.scivicslab.gpubroker.boot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.actor.AiServiceEndpoint;
import com.scivicslab.gpubroker.actor.AiServiceEndpointBuilder;
import com.scivicslab.gpubroker.actor.JobQueue;
import com.scivicslab.gpubroker.actor.ROOT;
import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.EndpointProbe;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.QueueStatus;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Builds the Actor tree at startup by surveying every known {@link
 * EndpointProbe} kind across every configured node, and drains it
 * gracefully at shutdown.
 *
 * <p>The only place in the codebase that {@code new}s a {@code ROOT} or a
 * {@code JobQueue} and wires it in with {@code actorOf}/{@code createChild}
 * — for the same reason {@code ActorSystemProducer} is the sole place that
 * {@code new}s the {@code ActorSystem}: a single, findable place to reason
 * about the tree's shape. {@code AiServiceEndpoint} instances themselves are
 * {@code new}'d by {@link AiServiceEndpointBuilder}, not here — this class
 * only {@code createChild}s the result.
 */
@Singleton
public class JobQueueRegistry {

    private static final Logger LOG = Logger.getLogger(JobQueueRegistry.class);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    @Inject
    ActorSystem system;

    @Inject
    EndpointSurveyor surveyor;

    @Inject
    AiServiceEndpointBuilder builder;

    @Inject
    ActorRef<JobQueueRegistryState> state;

    /** Kept past startup so {@link #reconcile} can hang new queues off the same root. */
    private ActorRef<ROOT> root;

    /**
     * Set once the startup survey has been registered. {@link #reconcile} does nothing until
     * then: the first poll may fire while {@code onStart} is still in its loop, and an address
     * registered by both would get a second {@code AiServiceEndpoint} whose {@code ActorRef}
     * silently replaces the first in the {@code ActorSystem}.
     */
    private volatile boolean ready;

    void onStart(@Observes StartupEvent event) {
        root = system.actorOf("root", new ROOT());
        for (EndpointSurveyor.Found found : surveyor.surveyNow()) {
            registerEndpoint(found.probe(), found.info());
        }
        ready = true;
    }

    /**
     * Brings the registry in line with one round of probing: registers an address that answers
     * but is not registered, and moves an address that now answers under a different queue name
     * (its node's model was replaced). Leaves a registered address that answered nothing alone —
     * a server that is merely restarting must not churn the actor tree.
     *
     * <p>Called once a minute by {@link EndpointPoller} with the same survey the status history
     * is recorded from. See {@code PeriodicRediscovery_260923_oo01}.
     */
    public void reconcile(List<EndpointSurveyor.Found> found) {
        if (!ready || isDraining()) {
            return;
        }
        Map<String, String> registered = state.ask(JobQueueRegistryState::endpointQueues).join();
        for (ReconcilePlan.Change change : ReconcilePlan.of(registered, found)) {
            if (change.leavingQueue() != null) {
                unregisterEndpoint(change.found().info().address(), change.leavingQueue());
            }
            registerEndpoint(change.found().probe(), change.found().info());
        }
    }

    void onShutdown(@Observes ShutdownEvent event) {
        state.tell(JobQueueRegistryState::setDraining).join();
        List<ActorRef<JobQueue>> allQueues = state.ask(JobQueueRegistryState::allQueues).join();
        for (ActorRef<JobQueue> queue : allQueues) {
            List<Job> pending = queue.ask(JobQueue::drainPending).join();
            pending.forEach(job -> job.responseSink().fail(new DrainingException()));
        }
        awaitIdle(DRAIN_TIMEOUT, allQueues);
    }

    public boolean isDraining() {
        return state.ask(JobQueueRegistryState::isDraining).join();
    }

    public ActorRef<JobQueue> get(String queueName) {
        return state.ask(s -> s.get(queueName)).join();
    }

    /** One {@link QueueStatus} per registered queue, for {@code GET /status}. */
    public List<QueueStatus> statusSnapshot() {
        return state.ask(JobQueueRegistryState::queueMap).join().entrySet().stream()
                .map(e -> new QueueStatus(e.getKey(), e.getValue().ask(JobQueue::snapshot).join()))
                .toList();
    }

    /** {@code queueName} -> {@link EndpointInfo#displayName}, for {@code OpenAiCompatResource}'s
     *  {@code GET /v1/models}. */
    public Map<String, String> displayNames() {
        return state.ask(JobQueueRegistryState::displayNames).join();
    }

    private void registerEndpoint(EndpointProbe probe, EndpointInfo info) {
        JobQueueRegistryState.Registration registration = state.ask(s ->
                s.registerQueue(info.queueName(), () -> root.createChild(info.queueName(), new JobQueue()))
        ).join();
        ActorRef<JobQueue> queue = registration.queue();
        if (registration.isNew()) {
            state.tell(s -> s.putDisplayName(info.queueName(), info.displayName()));
            queue.tell(q -> q.bind(system, queue));
            queue.tell(JobQueue::startReconciliation);
        }
        AiServiceEndpoint endpoint = builder.build(info, probe.requestPath());
        ActorRef<AiServiceEndpoint> endpointRef = queue.createChild(info.address(), endpoint);
        endpointRef.tell(e -> e.bind(system, endpointRef));
        endpointRef.tell(AiServiceEndpoint::start);
        state.tell(s -> s.putEndpoint(info.address(), info.queueName())).join();
        LOG.infof("discovered %s at %s -> queue %s (maxConcurrency=%d)",
                probe.getClass().getSimpleName(), info.address(), info.queueName(), info.maxConcurrency());
    }

    /**
     * Takes {@code address} out of {@code queueName}: withdraws each of its workers from the
     * queue, closes their actors and the endpoint's, and drops the queue itself once it has no
     * addresses left.
     *
     * <p>The workers are withdrawn by asking the queue directly rather than by telling each
     * worker to detach itself: a worker in the middle of a job would process that message only
     * after the job returned, and the job cannot return — the model that address served is gone.
     * Closing interrupts that call, which fails the job into the normal retry path
     * ({@code RetryLimit_260810_oo01}).
     */
    private void unregisterEndpoint(String address, String queueName) {
        ActorRef<JobQueue> queue = state.ask(s -> s.get(queueName)).join();
        ActorRef<AiServiceEndpoint> endpointRef = system.getActor(address);
        if (endpointRef != null) {
            List<String> workerNames = List.copyOf(endpointRef.getNamesOfChildren());
            if (queue != null) {
                for (String workerName : workerNames) {
                    queue.tell(q -> q.withdraw(workerName)).join();
                }
            }
            for (String workerName : workerNames) {
                ActorRef<?> worker = system.getActor(workerName);
                if (worker != null) {
                    worker.close();
                }
            }
            endpointRef.close();
        }
        state.tell(s -> s.removeEndpoint(address)).join();
        boolean stillServed = state.ask(s -> s.hasEndpoints(queueName)).join();
        if (!stillServed) {
            state.tell(s -> s.removeQueue(queueName)).join();
            LOG.infof("queue %s has no endpoints left; no longer advertised", queueName);
        }
        LOG.infof("withdrew %s from queue %s", address, queueName);
    }

    private void awaitIdle(Duration timeout, List<ActorRef<JobQueue>> allQueues) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline) && !allQueuesIdle(allQueues)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean allQueuesIdle(List<ActorRef<JobQueue>> allQueues) {
        return allQueues.stream().allMatch(q -> q.ask(JobQueue::isIdle).join());
    }
}
