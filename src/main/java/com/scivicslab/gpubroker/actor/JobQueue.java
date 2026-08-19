package com.scivicslab.gpubroker.actor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

/**
 * One capability's queue: a priority deque plus the set of
 * {@code AiServiceEndpoint}s (identified by {@code endpointId}, i.e. their
 * actor name) that serve it. Wrapped as a single POJO-actor so that
 * {@code submit}/{@code requestWork}/{@code withdraw}/{@code attach} are
 * serialized through one mailbox.
 *
 * <p>FOREGROUND jobs go to the front of {@link #deque}, BACKGROUND to the
 * back, so dispatch always taking from the front gives FG-over-BG ordering
 * for free, and an FG job is always at the front if one is waiting at all.
 *
 * <p>An {@code endpointId} that has just been handed a FOREGROUND job is
 * reserved for a few minutes — it will not be handed a BACKGROUND job
 * (from {@link #submit} or {@link #requestWork}) until the reservation
 * lapses, so a node that just finished an interactive turn stays free for
 * the next one in the same conversation. The reservation is not actively
 * cleared by a timer; it is just a timestamp compared against {@code now}
 * whenever this POJO is next asked to hand out work.
 *
 * <p>Holds an {@code ActorSystem} (via {@link #bind}) and periodically
 * re-checks its own idle endpoints ({@link #reconcileIdleEndpoints}) so a
 * job left waiting behind a since-lapsed reservation is not starved
 * forever by newer jobs — see
 * {@code JobQueueReservationStarvationBug_260819_oo01} for why this is
 * needed: {@link #submit} hands a freshly-idle endpoint directly to
 * whatever job just arrived, without ever looking at {@link #deque}.
 */
public final class JobQueue {

    private static final Duration DEFAULT_RESERVATION = Duration.ofMinutes(3);
    private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(30);

    private final Deque<Job> deque = new ArrayDeque<>();
    private final Deque<String> idleEndpointIds = new ArrayDeque<>();
    private final Set<String> activeEndpointIds = new HashSet<>();
    private final Map<String, Instant> reservedUntil = new HashMap<>();
    private final Duration reservation;
    private ActorSystem system;
    private ActorRef<JobQueue> self;

    public JobQueue() {
        this(DEFAULT_RESERVATION);
    }

    /** Package-visible: lets tests use a short reservation instead of waiting real minutes. */
    JobQueue(Duration reservation) {
        this.reservation = reservation;
    }

    /** Bind this actor's own reference; must run before {@link #startReconciliation}. */
    public void bind(ActorSystem system, ActorRef<JobQueue> self) {
        this.system = system;
        this.self = self;
    }

    /**
     * Starts the periodic re-check described in the class Javadoc. Scheduled against
     * {@code self}, not called directly, so {@link #reconcileIdleEndpoints} runs serialized
     * through this actor's own mailbox like every other message.
     */
    public void startReconciliation() {
        new Scheduler().scheduleWithFixedDelay("reconcile", self, JobQueue::reconcileIdleEndpoints,
                RECONCILE_INTERVAL.toSeconds(), RECONCILE_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Re-offers work to every currently-idle endpoint. A no-op for any endpoint whose
     * reservation (if any) still holds and has nothing FOREGROUND waiting — see
     * {@link #pollWork}, reused as-is from the {@link #requestWork} path.
     */
    void reconcileIdleEndpoints() {
        for (String endpointId : List.copyOf(idleEndpointIds)) {
            Job job = pollWork(endpointId);
            if (job != null) {
                idleEndpointIds.remove(endpointId);
                reserveIfForeground(endpointId, job);
                dispatch(endpointId, job);
            }
        }
    }

    private void dispatch(String endpointId, Job job) {
        ActorRef<AiServiceEndpointWorker> worker = system.getActor(endpointId);
        worker.tell(w -> w.assign(job));
    }

    /**
     * Enqueue a job, in its priority's position. Returns the id of the endpoint to hand it
     * to now, or null if parked.
     *
     * <p>Always dispatches {@link #deque}'s front, not necessarily {@code job} itself — an
     * older, same-or-lower-priority job already waiting there is served first. If that
     * front job is {@code job}, its endpointId is returned so the caller dispatches it
     * (the common, uncontended case, needing no help from {@link #system}). Otherwise this
     * method dispatches the older front job itself (see {@link #dispatch}) and returns
     * null — {@code job} stays queued for a later turn. Without this check, a newly idle
     * endpoint would always go to whichever job happens to arrive next, silently starving
     * anything already waiting — see {@code JobQueueReservationStarvationBug_260819_oo01}.
     */
    public String submit(Job job) {
        if (job.priority() == Priority.FOREGROUND) {
            deque.addFirst(job);
        } else {
            deque.addLast(job);
        }
        return dispatchFront(job);
    }

    /** Hands {@link #deque}'s front to an eligible idle endpoint, if any. See {@link #submit}. */
    private String dispatchFront(Job justSubmitted) {
        Job front = deque.peekFirst();
        String endpointId = pollIdleEndpoint(front.priority());
        if (endpointId == null) {
            return null;
        }
        deque.pollFirst();
        reserveIfForeground(endpointId, front);
        if (front == justSubmitted) {
            return endpointId;
        }
        dispatch(endpointId, front);
        return null;
    }

    /** An AiServiceEndpoint wants work. Returns the job to assign, or null (it parks idle). */
    public Job requestWork(String endpointId) {
        if (!activeEndpointIds.contains(endpointId)) {
            return null;
        }
        Job job = pollWork(endpointId);
        if (job == null) {
            idleEndpointIds.addLast(endpointId);
        } else {
            reserveIfForeground(endpointId, job);
        }
        return job;
    }

    public void withdraw(String endpointId) {
        activeEndpointIds.remove(endpointId);
        idleEndpointIds.remove(endpointId);
        reservedUntil.remove(endpointId);
    }

    public Job attach(String endpointId) {
        activeEndpointIds.add(endpointId);
        return requestWork(endpointId);
    }

    /** Take every job still waiting (not yet handed to any endpoint) for graceful drain. */
    public List<Job> drainPending() {
        List<Job> pending = new ArrayList<>(deque);
        deque.clear();
        return pending;
    }

    /** Whether every attached endpoint is idle (nothing currently in flight). */
    public boolean isIdle() {
        return activeEndpointIds.size() == idleEndpointIds.size();
    }

    /** Current state for the status page — read-only, does not mutate anything. */
    public QueueSnapshot snapshot() {
        Set<String> idle = new LinkedHashSet<>(idleEndpointIds);
        Set<String> active = new LinkedHashSet<>(activeEndpointIds);
        active.removeAll(idle);
        return new QueueSnapshot(List.copyOf(active), List.copyOf(idle), deque.size());
    }

    private String pollIdleEndpoint(Priority priority) {
        Iterator<String> it = idleEndpointIds.iterator();
        while (it.hasNext()) {
            String endpointId = it.next();
            if (priority == Priority.BACKGROUND && isReserved(endpointId)) {
                continue;
            }
            it.remove();
            return endpointId;
        }
        return null;
    }

    private Job pollWork(String endpointId) {
        if (isReserved(endpointId)) {
            Job front = deque.peekFirst();
            return (front != null && front.priority() == Priority.FOREGROUND) ? deque.pollFirst() : null;
        }
        return deque.pollFirst();
    }

    private void reserveIfForeground(String endpointId, Job job) {
        if (job.priority() == Priority.FOREGROUND) {
            reservedUntil.put(endpointId, Instant.now().plus(reservation));
        }
    }

    private boolean isReserved(String endpointId) {
        Instant until = reservedUntil.get(endpointId);
        return until != null && Instant.now().isBefore(until);
    }
}
