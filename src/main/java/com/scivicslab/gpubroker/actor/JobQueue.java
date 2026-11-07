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
import java.util.function.ToDoubleFunction;

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
 *
 * <p>An address (the {@code host:port} in front of the {@code #} of an
 * endpointId) may count a FOREGROUND job as more than one slot ({@code
 * foreground-background-slot-ratio}, read through {@link #foregroundWeight}),
 * so that a server sized for a few long conversations takes many more short
 * BACKGROUND jobs without running out of memory when both come. The address's
 * load is its slots running FOREGROUND work times the ratio, plus its slots
 * running BACKGROUND work, plus its idle slots reserved for FOREGROUND work
 * (one each: they hold a slot, not memory); a job takes a slot only if the
 * load with its own share stays within the address's slots. While a
 * FOREGROUND job waits at the front of {@link #deque}, a BACKGROUND job takes
 * a slot only if room for that FOREGROUND job is still left after it, so
 * BACKGROUND work that keeps arriving cannot starve the FOREGROUND job: the
 * running BACKGROUND jobs finish and the room they leave goes to it. With no
 * ratio (1), the load never exceeds the slots, which is the behaviour of
 * before.
 */
public final class JobQueue {

    private static final Duration DEFAULT_RESERVATION = Duration.ofMinutes(3);
    private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(30);

    private final Deque<Job> deque = new ArrayDeque<>();
    private final Deque<String> idleEndpointIds = new ArrayDeque<>();
    private final Set<String> activeEndpointIds = new HashSet<>();
    private final Map<String, Instant> reservedUntil = new HashMap<>();
    private final Set<String> foregroundBusy = new HashSet<>();
    private final Set<String> backgroundBusy = new HashSet<>();
    private final Duration reservation;
    private final ToDoubleFunction<String> foregroundWeight;
    private long completedTotal;
    private long failedTotal;
    private ActorSystem system;
    private ActorRef<JobQueue> self;

    public JobQueue() {
        this(DEFAULT_RESERVATION, address -> 1);
    }

    /**
     * @param foregroundWeight an address's {@code foreground-background-slot-ratio}, or 1 for an
     *                         address without one; asked anew at every dispatch, so an edit to the
     *                         configuration takes effect without a restart
     */
    public JobQueue(ToDoubleFunction<String> foregroundWeight) {
        this(DEFAULT_RESERVATION, foregroundWeight);
    }

    /** Package-visible: lets tests use a short reservation instead of waiting real minutes. */
    JobQueue(Duration reservation) {
        this(reservation, address -> 1);
    }

    JobQueue(Duration reservation, ToDoubleFunction<String> foregroundWeight) {
        this.reservation = reservation;
        this.foregroundWeight = foregroundWeight;
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
                assigned(endpointId, job);
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

    /**
     * Hands {@link #deque}'s front to an eligible idle endpoint, if any; if the front is a
     * FOREGROUND job with no room anywhere, hands the first BACKGROUND job instead where that
     * still leaves room for it. See {@link #submit}.
     */
    private String dispatchFront(Job justSubmitted) {
        Job next = deque.peekFirst();
        String endpointId = pollIdleEndpoint(next.priority());
        if (endpointId == null && next.priority() == Priority.FOREGROUND) {
            next = firstBackground();
            endpointId = next == null ? null : pollIdleEndpoint(Priority.BACKGROUND);
        }
        if (endpointId == null) {
            return null;
        }
        take(next);
        assigned(endpointId, next);
        if (next == justSubmitted) {
            return endpointId;
        }
        dispatch(endpointId, next);
        return null;
    }

    /** An AiServiceEndpoint wants work. Returns the job to assign, or null (it parks idle). */
    public Job requestWork(String endpointId) {
        // The job it ran, if any, is over: it no longer counts in its address's load.
        foregroundBusy.remove(endpointId);
        backgroundBusy.remove(endpointId);
        if (!activeEndpointIds.contains(endpointId)) {
            return null;
        }
        Job job = pollWork(endpointId);
        if (job == null) {
            idleEndpointIds.addLast(endpointId);
        } else {
            assigned(endpointId, job);
            // Counted with this job's own slot already taken out of the idle set by pollWork.
            job.responseSink().slotsInUse(busySlots(), totalSlots());
        }
        return job;
    }

    public void withdraw(String endpointId) {
        activeEndpointIds.remove(endpointId);
        idleEndpointIds.remove(endpointId);
        reservedUntil.remove(endpointId);
        foregroundBusy.remove(endpointId);
        backgroundBusy.remove(endpointId);
    }

    public Job attach(String endpointId) {
        activeEndpointIds.add(endpointId);
        return requestWork(endpointId);
    }

    /**
     * Slots generating right now, and slots attached. Read from inside this actor's mailbox,
     * which is the only place the two sets are consistent with each other
     * ({@code GenerationRateWindowsAndLayout_260923_oo01}).
     */
    public int busySlots() {
        return activeEndpointIds.size() - idleEndpointIds.size();
    }

    public int totalSlots() {
        return activeEndpointIds.size();
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

    /**
     * One job finished successfully on some {@code AiServiceEndpointWorker} of this queue.
     * Counted here rather than in the worker because the worker already sends this queue a
     * message the moment it finishes ({@code requestWork}), so no new path is needed — and
     * the count belongs with the rest of this queue's state.
     */
    public void recordCompleted() {
        completedTotal++;
    }

    /** One job was given up on after exhausting its attempts — see {@code AiServiceEndpointWorker.requeue}. */
    public void recordFailed() {
        failedTotal++;
    }

    /** Current state for the status page — read-only, does not mutate anything. */
    public QueueSnapshot snapshot() {
        Set<String> idle = new LinkedHashSet<>(idleEndpointIds);
        Set<String> active = new LinkedHashSet<>(activeEndpointIds);
        active.removeAll(idle);
        return new QueueSnapshot(List.copyOf(active), List.copyOf(idle), deque.size(), completedTotal, failedTotal);
    }

    private String pollIdleEndpoint(Priority priority) {
        Iterator<String> it = idleEndpointIds.iterator();
        while (it.hasNext()) {
            String endpointId = it.next();
            if (priority == Priority.BACKGROUND && !mayTakeBackground(endpointId)) {
                continue;
            }
            if (priority == Priority.FOREGROUND && !mayTakeForeground(endpointId)) {
                continue;
            }
            it.remove();
            return endpointId;
        }
        return null;
    }

    private Job pollWork(String endpointId) {
        Job front = deque.peekFirst();
        if (front == null) {
            return null;
        }
        if (front.priority() == Priority.FOREGROUND && mayTakeForeground(endpointId)) {
            return takeFront();
        }
        if (!mayTakeBackground(endpointId)) {
            return null;
        }
        // The front is BACKGROUND, or FOREGROUND with no room on this address yet.
        Job background = front.priority() == Priority.BACKGROUND ? front : firstBackground();
        if (background != null) {
            take(background);
        }
        return background;
    }

    /** Whether a FOREGROUND job may run on this slot: its address has room for the job's share. */
    private boolean mayTakeForeground(String endpointId) {
        String address = addressOf(endpointId);
        return loadWithout(endpointId) + foregroundWeight.applyAsDouble(address) <= slotsOf(address);
    }

    /**
     * Whether a BACKGROUND job may run on this slot: it is not reserved for FOREGROUND work, its
     * address has room for one more, and, while a FOREGROUND job waits at the front, room for that
     * job is still left after this one.
     */
    private boolean mayTakeBackground(String endpointId) {
        if (isReserved(endpointId)) {
            return false;
        }
        String address = addressOf(endpointId);
        double need = 1;
        Job front = deque.peekFirst();
        if (front != null && front.priority() == Priority.FOREGROUND) {
            need += foregroundWeight.applyAsDouble(address);
        }
        return loadWithout(endpointId) + need <= slotsOf(address);
    }

    /** The load of {@code endpointId}'s address, leaving out {@code endpointId} itself (the slot being decided on). */
    private double loadWithout(String endpointId) {
        String address = addressOf(endpointId);
        double weight = foregroundWeight.applyAsDouble(address);
        double load = 0;
        for (String id : activeEndpointIds) {
            if (id.equals(endpointId) || !addressOf(id).equals(address)) {
                continue;
            }
            if (foregroundBusy.contains(id)) {
                load += weight;
            } else if (backgroundBusy.contains(id) || isReserved(id)) {
                load += 1;
            }
        }
        // A reserved slot taking the job holds its slot already; counting it as free here is
        // right, since the job's own share is added by the caller.
        return load;
    }

    private int slotsOf(String address) {
        int slots = 0;
        for (String id : activeEndpointIds) {
            if (addressOf(id).equals(address)) {
                slots++;
            }
        }
        return slots;
    }

    /** The {@code host:port} of a slot named {@code host:port#N}; the whole id if it has no {@code #}. */
    static String addressOf(String endpointId) {
        int hash = endpointId.lastIndexOf('#');
        return hash < 0 ? endpointId : endpointId.substring(0, hash);
    }

    private Job firstBackground() {
        for (Job job : deque) {
            if (job.priority() == Priority.BACKGROUND) {
                return job;
            }
        }
        return null;
    }

    /** Removes {@code job} from {@link #deque}, wherever it is, and tells it that it is no longer queued. */
    private void take(Job job) {
        // By identity, not equals: two waiting jobs may hold equal values.
        Iterator<Job> it = deque.iterator();
        while (it.hasNext()) {
            if (it.next() == job) {
                it.remove();
                break;
            }
        }
        job.responseSink().dispatched();
    }

    /**
     * Removes the front job and tells it that it is no longer queued. The one place a job
     * leaves {@link #deque} on its way to a worker — {@link #drainPending} is the other way
     * out and deliberately does not report a dispatch, since those jobs never reach one.
     */
    private Job takeFront() {
        Job job = deque.pollFirst();
        if (job != null) {
            job.responseSink().dispatched();
        }
        return job;
    }

    /** {@code job} has been handed to {@code endpointId}: it counts in the address's load, and a FOREGROUND job reserves the slot. */
    private void assigned(String endpointId, Job job) {
        if (job.priority() == Priority.FOREGROUND) {
            reservedUntil.put(endpointId, Instant.now().plus(reservation));
            foregroundBusy.add(endpointId);
        } else {
            backgroundBusy.add(endpointId);
        }
    }

    private boolean isReserved(String endpointId) {
        Instant until = reservedUntil.get(endpointId);
        return until != null && Instant.now().isBefore(until);
    }
}
