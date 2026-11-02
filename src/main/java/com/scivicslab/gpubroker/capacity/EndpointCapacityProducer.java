package com.scivicslab.gpubroker.capacity;

import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The single place that wraps {@link EndpointCapacity} as an actor. Its declarations are kept in
 * the same directory as the submit-then-poll jobs ({@code gpu.broker.jobs-dir}), on the broker's
 * persistent volume; without that property they are kept in memory only.
 */
@Singleton
public class EndpointCapacityProducer {

    @ConfigProperty(name = "gpu.broker.jobs-dir")
    Optional<String> jobsDir;

    @Produces
    @Singleton
    public ActorRef<EndpointCapacity> endpointCapacity(ActorSystem system) {
        return system.actorOf("endpoint-capacity", new EndpointCapacity(jobsDir.map(Path::of).orElse(null)));
    }
}
