package com.scivicslab.gpubroker.boot;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.scivicslab.gpubroker.actor.NodeActor;
import com.scivicslab.gpubroker.actor.QueueActor;
import com.scivicslab.gpubroker.llm.HttpUpstreamLlmClient;
import com.scivicslab.gpubroker.llm.UpstreamLlmClient;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Owns the broker's actor system: one {@link QueueActor} and one
 * {@link NodeActor} per configured upstream vLLM URL.
 *
 * <p>At startup each node is bound and enters rotation (idle). The actors live
 * for the whole process; the JAX-RS resource submits jobs to {@link #queue()}.
 * Node URLs come from {@code gpu-broker.node-urls} (comma-separated).
 */
@ApplicationScoped
public class GatewayActors {

    @ConfigProperty(name = "gpu-broker.node-urls")
    Optional<List<String>> nodeUrls;

    private ActorSystem system;
    private ActorRef<QueueActor> queue;

    void onStart(@Observes StartupEvent event) {
        system = new ActorSystem("gpu-broker");
        queue = system.actorOf("queue", new QueueActor());
        UpstreamLlmClient upstream = new HttpUpstreamLlmClient();

        List<String> urls = nodeUrls.orElse(List.of());
        int index = 0;
        for (String url : urls) {
            String name = "node-" + (index++);
            NodeActor node = new NodeActor(url, queue, upstream);
            ActorRef<NodeActor> ref = system.actorOf(name, node);
            ref.tell(n -> n.bind(ref)).join();   // bind self before start
            ref.tell(NodeActor::start);          // enter rotation (attach → idle)
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        if (system != null) {
            system.terminate();
        }
    }

    public ActorRef<QueueActor> queue() {
        return queue;
    }
}
