package com.scivicslab.gpubroker.actor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.gpubroker.model.Priority;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Single priority deque plus an idle-node set that establishes the global
 * FG-over-BG ordering (S_base) and the completion-driven dispatch (S_dispatch).
 *
 * <p>One {@link Deque} holds all waiting jobs: foreground jobs go to the front,
 * background jobs to the back, and dispatch always takes from the front, so a
 * foreground request overtakes the background backlog without a comparator.
 *
 * <p>A second deque holds the {@code NodeActor}s that are waiting for work. The
 * two halves meet in {@link #submit} and {@link #requestWork}: whichever side
 * has a waiting counterpart triggers an {@code assign}; otherwise the job (or the
 * node) parks. Because a busy node is neither in the idle set nor sends
 * {@code requestWork} until it completes, a node is never handed a second job —
 * N=1 holds structurally.
 *
 * <p>In production this class is wrapped as a single POJO-actor so that
 * {@code submit}, {@code requestWork} (and the {@code withdraw} added later) are
 * serialized through one mailbox, keeping it a single writer without locks.
 */
public final class QueueActor {

    private final Deque<Job> deque = new ArrayDeque<>();
    private final Deque<ActorRef<NodeActor>> idle = new ArrayDeque<>();
    private final Set<ActorRef<NodeActor>> active = new HashSet<>();

    /** Enqueue a job: hand it to a waiting node, else park it FG-front / BG-back. */
    public void submit(Job job) {
        ActorRef<NodeActor> node = idle.pollFirst();
        if (node != null) {
            node.tell(n -> n.assign(job));   // a node is waiting → hand it directly
        } else if (job.priority() == Priority.FOREGROUND) {
            deque.addFirst(job);             // FG: front
        } else {
            deque.addLast(job);              // BG: back
        }
    }

    /** A node finished (or just started) and wants the next job, FG-first. */
    public void requestWork(ActorRef<NodeActor> node) {
        if (!active.contains(node)) {
            return;                          // detached/unhealthy → give it nothing
        }
        Job job = pollNext();                // FG-first
        if (job != null) {
            node.tell(n -> n.assign(job));   // work available → assign now
        } else {
            idle.addLast(node);              // nothing to do → park as idle
        }
    }

    /** Drop a node from rotation (detach or unhealthy). No drain: jobs aren't node-bound. */
    public void withdraw(ActorRef<NodeActor> node) {
        active.remove(node);
        idle.remove(node);
    }

    /** Put a node into rotation and immediately let it pick up work if available. */
    public void attach(ActorRef<NodeActor> node) {
        active.add(node);
        requestWork(node);
    }

    /** Take the highest-priority waiting job, or {@code null} when empty. */
    public Job pollNext() {
        return deque.pollFirst();            // FG-first; null when empty
    }
}
