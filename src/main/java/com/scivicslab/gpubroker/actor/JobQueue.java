package com.scivicslab.gpubroker.actor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.gpubroker.model.QueueSnapshot;

/**
 * One capability's queue: a priority deque plus the set of
 * {@code AiServiceEndpoint}s (identified by {@code endpointId}, i.e. their
 * actor name) that serve it. A plain POJO — no {@code ActorRef} fields, no
 * {@code tell} to anyone — wrapped as a single POJO-actor so that
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
 */
public final class JobQueue {

    private static final Duration DEFAULT_RESERVATION = Duration.ofMinutes(3);

    private final Deque<Job> deque = new ArrayDeque<>();
    private final Deque<String> idleEndpointIds = new ArrayDeque<>();
    private final Set<String> activeEndpointIds = new HashSet<>();
    private final Map<String, Instant> reservedUntil = new HashMap<>();
    private final Duration reservation;

    public JobQueue() {
        this(DEFAULT_RESERVATION);
    }

    /** Package-visible: lets tests use a short reservation instead of waiting real minutes. */
    JobQueue(Duration reservation) {
        this.reservation = reservation;
    }

    /** Enqueue a job. Returns the id of the AiServiceEndpoint to hand it to now, or null if parked. */
    public String submit(Job job) {
        String endpointId = pollIdleEndpoint(job.priority());
        if (endpointId != null) {
            reserveIfForeground(endpointId, job);
            return endpointId;
        }
        if (job.priority() == Priority.FOREGROUND) {
            deque.addFirst(job);
        } else {
            deque.addLast(job);
        }
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

    /** Current counts for {@code GET /status} — read-only, does not mutate state. */
    public QueueSnapshot snapshot() {
        int idle = idleEndpointIds.size();
        int active = activeEndpointIds.size() - idle;
        return new QueueSnapshot(active, idle, deque.size());
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
