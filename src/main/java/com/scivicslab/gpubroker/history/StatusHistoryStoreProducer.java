package com.scivicslab.gpubroker.history;

import java.nio.file.Path;
import java.time.Instant;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The single place that decides where the status history file lives and
 * loads it back at startup — the same role {@code ActorSystemProducer} plays
 * for the {@code ActorSystem}.
 *
 * <p>{@code broker.history.file} is read here rather than inside {@link
 * StatusHistoryStore} so that the store stays a plain object a unit test can
 * construct against a temporary directory.
 */
@Singleton
public class StatusHistoryStoreProducer {

    @ConfigProperty(name = "broker.history.file",
            defaultValue = "${user.home}/.local/share/gpu-broker/history.jsonl")
    String historyFilePath;

    @Produces
    @Singleton
    public StatusHistoryStore statusHistoryStore() {
        StatusHistoryStore store = new StatusHistoryStore(Path.of(historyFilePath));
        store.load(Instant.now());
        return store;
    }
}
