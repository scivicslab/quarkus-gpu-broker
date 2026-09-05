package com.scivicslab.gpubroker.history;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

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

    /** Relative to the user's home directory, when {@code broker.history.file} is not set. */
    static final String DEFAULT_RELATIVE_PATH = ".local/share/gpu-broker/history.jsonl";

    @ConfigProperty(name = "broker.history.file")
    Optional<String> configuredPath;

    @Produces
    @Singleton
    public StatusHistoryStore statusHistoryStore() {
        StatusHistoryStore store = new StatusHistoryStore(historyFile(configuredPath));
        store.load(Instant.now());
        return store;
    }

    /**
     * The configured path, or the default under the user's home directory.
     *
     * <p>The home directory is resolved here, in Java, rather than written as {@code
     * ${user.home}/...} in the annotation's {@code defaultValue}: a property expression there is
     * taken literally, and the broker then writes its history into a directory whose name is the
     * eight characters {@code ${user.home}}, created wherever it happens to have been started
     * from. This was observed on 2026-09-05 — {@code ~/works/${user.home}/.local/share/gpu-broker/}
     * held 207 lines of real history while the intended path did not exist.
     */
    static Path historyFile(Optional<String> configuredPath) {
        return configuredPath
                .filter(path -> !path.isBlank())
                .map(Path::of)
                .orElseGet(() -> Path.of(System.getProperty("user.home"), DEFAULT_RELATIVE_PATH));
    }
}
