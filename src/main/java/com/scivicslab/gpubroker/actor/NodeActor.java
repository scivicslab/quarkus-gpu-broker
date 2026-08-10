package com.scivicslab.gpubroker.actor;

import org.jboss.logging.Logger;

import com.scivicslab.gpubroker.llm.UpstreamException;
import com.scivicslab.gpubroker.llm.UpstreamLlmClient;
import com.scivicslab.gpubroker.model.Job;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * Drives one GPU node (one standalone vLLM) with completion-driven pull
 * (transition S_base -&gt; S_dispatch).
 *
 * <p>On {@link #assign} the actor sends the job to its vLLM through
 * {@link UpstreamLlmClient} and blocks its own virtual thread until the remote
 * response completes, then asks the {@code QueueActor} for the next job with
 * {@link #requestWork}. A fresh job is therefore pulled only when the previous
 * one finishes (closed loop), so the node never idles and never over-commits.
 *
 * <p>Because a POJO-actor processes one message at a time, while {@code assign}
 * blocks on the upstream wait this actor handles no other message — N=1 per node
 * holds with no explicit free/busy flag.
 */
public final class NodeActor {

    private static final Logger LOG = Logger.getLogger(NodeActor.class);

    private final String url;                      // this node's vLLM URL
    private final ActorRef<QueueActor> queue;
    private final UpstreamLlmClient upstream;
    private ActorRef<NodeActor> self;              // bound right after actorOf

    public NodeActor(String url, ActorRef<QueueActor> queue, UpstreamLlmClient upstream) {
        this.url = url;
        this.queue = queue;
        this.upstream = upstream;
    }

    /** Bind this actor's own reference; must run before {@link #start}. */
    public void bind(ActorRef<NodeActor> self) {
        this.self = self;
    }

    /** Enter rotation so the queue can hand this node its first job. */
    public void start() {
        queue.tell(q -> q.attach(self));
    }

    /** Process one job to completion, then pull the next (completion-driven). */
    public void assign(Job job) {
        LOG.infof("assign begin: node=%s job=%s prio=%s", url, job.id(), job.priority());
        try {
            upstream.send(url, job);               // blocks this virtual thread until vLLM is done
        } catch (UpstreamException e) {            // this node failed mid-flight
            LOG.warnf("assign failed: node=%s job=%s (%s) → re-submit + withdraw", url, job.id(), e.getMessage());
            queue.tell(q -> q.submit(job));        // re-submit → a healthy node takes it
            queue.tell(q -> q.withdraw(self));     // drop myself from rotation
            return;
        }
        LOG.infof("assign done: node=%s job=%s", url, job.id());
        queue.tell(q -> q.requestWork(self));      // completed → ask for the next
    }

    /** Hand this GPU to another use (training); stop receiving work. */
    public void detach() {
        queue.tell(q -> q.withdraw(self));
    }

    /** Return to service after a detach or recovery. */
    public void attach() {
        queue.tell(q -> q.attach(self));
    }
}
