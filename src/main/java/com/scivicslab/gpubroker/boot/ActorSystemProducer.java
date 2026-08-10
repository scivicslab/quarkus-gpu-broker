package com.scivicslab.gpubroker.boot;

import com.scivicslab.pojoactor.core.ActorSystem;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Registers the process's one {@code ActorSystem} instance as a CDI Bean.
 * Any other Bean that needs it declares {@code @Inject ActorSystem system;}
 * without knowing this class exists — Quarkus resolves it at build time via
 * its Jandex index.
 */
@Singleton
public class ActorSystemProducer {

    @Produces
    @Singleton
    ActorSystem produceActorSystem() {
        return new ActorSystem("gpu-broker");   // the only `new ActorSystem(...)` in the codebase
    }

    void onStop(@Observes ShutdownEvent event, ActorSystem system) {
        system.terminate();
    }
}
