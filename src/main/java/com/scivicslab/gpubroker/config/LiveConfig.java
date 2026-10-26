package com.scivicslab.gpubroker.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import com.scivicslab.gpubroker.model.Priority;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The {@code broker.*} tables, read from the configuration file again whenever it changes, so an
 * operator's edit reaches a running broker without a restart.
 *
 * <p>{@link BrokerConfig} cannot do this: Quarkus builds a {@code @ConfigMapping} once, at startup,
 * and a running process keeps that copy however the file changes afterwards. This class re-reads the
 * file Quarkus itself reads ({@code config/application.yaml} relative to the working directory)
 * and hands out the tables from the latest good reading. The file is looked at on every call by its
 * modification time and size, and parsed only when either has changed.
 *
 * <p>Tables read here: {@code capabilities}, {@code generation-limits}, {@code image-input},
 * {@code background-submitters} and {@code nodes}. When the file has never been readable (absent, or
 * a development run configured by other means) the startup copy in {@link BrokerConfig} is used.
 * A file that fails to parse after a good reading is ignored and the last good reading kept: a
 * half-written file must not reset every node to its default.
 */
@Singleton
public class LiveConfig {

    private static final Logger LOG = Logger.getLogger(LiveConfig.class);
    private static final String ABSENT = "absent";

    /** One reading of the tables. */
    public record Snapshot(Map<String, BrokerConfig.EndpointCapability> capabilities,
                           Map<String, BrokerConfig.QueueGenerationLimit> generationLimits,
                           Map<String, Boolean> imageInput,
                           Set<String> backgroundSubmitters,
                           Optional<List<String>> nodes) {}

    @Inject
    BrokerConfig startup;

    @ConfigProperty(name = "gpu.broker.capability-file", defaultValue = "config/application.yaml")
    String path;

    /** The latest good reading, or null before the first call. */
    private volatile Snapshot lastGood;
    /** The modification time and size of the file when {@link #lastGood} was settled. */
    private volatile String stamp = "";
    /** Whether the previous attempt failed, so a persistent failure is logged once, not every call. */
    private volatile boolean lastReadFailed;

    /** The tables to use now. */
    public Snapshot current() {
        Path file = Path.of(path);
        String now = stampOf(file);
        Snapshot held = lastGood;
        if (held != null && now.equals(stamp)) {
            return held;
        }
        return reload(file, now);
    }

    public Map<String, BrokerConfig.EndpointCapability> capabilities() {
        return current().capabilities();
    }

    public Map<String, Boolean> imageInput() {
        return current().imageInput();
    }

    public List<String> nodes() {
        return current().nodes().orElse(List.of());
    }

    /** This queue's limits: its own entry, or the one under {@code *} when it has none; null if neither. */
    public BrokerConfig.QueueGenerationLimit generationLimitFor(String queueName) {
        Map<String, BrokerConfig.QueueGenerationLimit> limits = current().generationLimits();
        BrokerConfig.QueueGenerationLimit own = limits.get(queueName);
        return own != null ? own : limits.get(BrokerConfig.ANY_QUEUE);
    }

    /**
     * The priority a job runs at: {@code requested}, unless the submitter is listed under
     * {@code background-submitters}, in which case it is always {@link Priority#BACKGROUND}.
     *
     * <p>The list lets an operator move a caller to background after the fact, for a caller that
     * forgets to say so itself ({@code X-Job-Priority}) -- the default for the chat paths is
     * foreground, which puts a bulk job ahead of an interactive one.
     */
    public Priority priorityFor(String submitterId, Priority requested) {
        if (submitterId != null && current().backgroundSubmitters().contains(submitterId)) {
            return Priority.BACKGROUND;
        }
        return requested;
    }

    private synchronized Snapshot reload(Path file, String now) {
        Snapshot held = lastGood;
        if (held != null && now.equals(stamp)) {
            return held;
        }
        if (ABSENT.equals(now)) {
            // Never read: use the startup copy. Read before: keep the reading (the file can be
            // missing for an instant while a mounted directory is swapped).
            if (held == null) {
                held = fromStartup();
                lastGood = held;
            }
            stamp = now;
            return held;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Snapshot read = parse(reader, startup.nodes());
            boolean changed = held != null;
            lastGood = read;
            stamp = now;
            lastReadFailed = false;
            if (changed) {
                LOG.infof("reloaded %s: %d capabilities, %d generation limits, %d image-input entries, "
                        + "%d background submitters", path, read.capabilities().size(),
                        read.generationLimits().size(), read.imageInput().size(),
                        read.backgroundSubmitters().size());
            }
            return read;
        } catch (IOException | RuntimeException e) {
            if (!lastReadFailed) {
                LOG.warnf(e, "could not read %s; keeping the previous tables", path);
                lastReadFailed = true;
            }
            // The same unreadable bytes are not tried again on every call; a new write changes the stamp.
            stamp = now;
            if (held == null) {
                held = fromStartup();
                lastGood = held;
            }
            return held;
        }
    }

    private Snapshot fromStartup() {
        return new Snapshot(startup.capabilities(), startup.generationLimits(), startup.imageInput(),
                new HashSet<>(startup.backgroundSubmitters().orElse(List.of())), startup.nodes());
    }

    private static String stampOf(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.lastModifiedTime().toMillis() + ":" + attributes.size();
        } catch (IOException e) {
            return ABSENT;
        }
    }

    /**
     * The tables of one YAML document. A table the document does not have is empty, which is a
     * valid answer: every node then runs at its kind's default, no queue has a limit, and so on.
     *
     * @param fallbackNodes the nodes to report when the document has none (the startup value, which
     *                      may come from an environment variable rather than from the file)
     * @throws IllegalArgumentException if a table is not the shape it has (a value that is not a
     *                                  map, a non-numeric concurrency)
     */
    static Snapshot parse(Reader yaml, Optional<List<String>> fallbackNodes) {
        Object document = new Yaml().load(yaml);
        Map<?, ?> broker = document instanceof Map<?, ?> root && root.get("broker") instanceof Map<?, ?> b ? b : Map.of();
        return new Snapshot(
                capabilities(broker.get("capabilities")),
                generationLimits(broker.get("generation-limits")),
                imageInput(broker.get("image-input")),
                names(broker.get("background-submitters"), "broker.background-submitters"),
                nodesOrFallback(broker.get("nodes"), fallbackNodes));
    }

    private static Map<String, BrokerConfig.EndpointCapability> capabilities(Object table) {
        Map<String, BrokerConfig.EndpointCapability> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : entriesOf(table, "broker.capabilities")) {
            Map<?, ?> fields = mapOf(entry.getValue(), "broker.capabilities." + entry.getKey());
            result.put(String.valueOf(entry.getKey()), new DeclaredCapability(
                    intField(fields, "max-concurrency"),
                    intField(fields, "max-context-length"),
                    boolField(fields, "thinking-mode-supported"),
                    boolField(fields, "tool-calling-supported")));
        }
        return result;
    }

    private static Map<String, BrokerConfig.QueueGenerationLimit> generationLimits(Object table) {
        Map<String, BrokerConfig.QueueGenerationLimit> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : entriesOf(table, "broker.generation-limits")) {
            Map<?, ?> fields = mapOf(entry.getValue(), "broker.generation-limits." + entry.getKey());
            result.put(String.valueOf(entry.getKey()), new DeclaredLimit(
                    intField(fields, "max-tokens"),
                    doubleField(fields, "repetition-penalty"),
                    doubleField(fields, "frequency-penalty")));
        }
        return result;
    }

    private static Map<String, Boolean> imageInput(Object table) {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : entriesOf(table, "broker.image-input")) {
            if (!(entry.getValue() instanceof Boolean flag)) {
                throw new IllegalArgumentException("broker.image-input." + entry.getKey() + " is not true or false");
            }
            result.put(String.valueOf(entry.getKey()), flag);
        }
        return result;
    }

    private static Set<String> names(Object list, String where) {
        Set<String> result = new HashSet<>();
        if (list == null) {
            return result;
        }
        if (!(list instanceof List<?> items)) {
            throw new IllegalArgumentException(where + " is not a list");
        }
        for (Object item : items) {
            result.add(String.valueOf(item));
        }
        return result;
    }

    private static Optional<List<String>> nodesOrFallback(Object nodes, Optional<List<String>> fallback) {
        if (nodes == null) {
            return fallback;
        }
        List<String> result = new ArrayList<>();
        if (nodes instanceof List<?> items) {
            items.forEach(item -> result.add(String.valueOf(item)));
        } else {
            for (String part : String.valueOf(nodes).split(",")) {
                if (!part.isBlank()) {
                    result.add(part.strip());
                }
            }
        }
        return Optional.of(result);
    }

    private static Iterable<? extends Map.Entry<?, ?>> entriesOf(Object table, String where) {
        if (table == null) {
            return List.of();
        }
        return mapOf(table, where).entrySet();
    }

    private static Map<?, ?> mapOf(Object value, String where) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(where + " is not a map");
    }

    private static OptionalInt intField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (value instanceof Number number) {
            return OptionalInt.of(number.intValue());
        }
        throw new IllegalArgumentException(name + " is not a number: " + value);
    }

    private static OptionalDouble doubleField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return OptionalDouble.empty();
        }
        if (value instanceof Number number) {
            return OptionalDouble.of(number.doubleValue());
        }
        throw new IllegalArgumentException(name + " is not a number: " + value);
    }

    private static Optional<Boolean> boolField(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        return value instanceof Boolean flag ? Optional.of(flag) : Optional.empty();
    }

    /** One host:port's entry as written in the file. */
    private record DeclaredCapability(OptionalInt maxConcurrency, OptionalInt maxContextLength,
                                      Optional<Boolean> thinkingModeSupported,
                                      Optional<Boolean> toolCallingSupported)
            implements BrokerConfig.EndpointCapability {}

    /** One queue's entry as written in the file. */
    private record DeclaredLimit(OptionalInt maxTokens, OptionalDouble repetitionPenalty,
                                 OptionalDouble frequencyPenalty)
            implements BrokerConfig.QueueGenerationLimit {}
}
