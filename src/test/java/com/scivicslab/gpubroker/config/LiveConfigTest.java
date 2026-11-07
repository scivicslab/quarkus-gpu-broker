package com.scivicslab.gpubroker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.gpubroker.model.Priority;

/**
 * {@link LiveConfig} is what lets an edit to the configuration file reach a running broker: it
 * looks at the file on every call and parses it again when it has changed. These tests use a real
 * file in a temporary directory -- the point is that a later read sees what the first did not.
 */
@Tag("ResizeWithoutRestart_261025_oo01")
@DisplayName("LiveConfig — the broker.* tables read again from the file")
class LiveConfigTest {

    private static final String SAMPLE = """
            broker:
              capabilities:
                # a comment between entries
                "192.168.5.13:8000":
                  max-concurrency: 1
                "192.168.5.16:8000":
                  max-concurrency: 4
                  max-context-length: 1048576
                  thinking-mode-supported: true
              generation-limits:
                "*":
                  max-tokens: 8192
                "chat-qwen3.8-flash-next":
                  max-tokens: 32768
                  frequency-penalty: 0.3
              image-input:
                "chat-google-gemma-4-26B-A4B-it": true
                "chat-text-only": false
              background-submitters:
                - rst-frame-import
                - nightly-batch
            """;

    /** A startup copy that says something recognisably different from any file the tests write. */
    private static BrokerConfig startupCopy(Map<String, BrokerConfig.EndpointCapability> capabilities,
                                            Optional<List<String>> nodes) {
        return new BrokerConfig() {
            @Override public Optional<List<String>> nodes() { return nodes; }
            @Override public Map<String, EndpointCapability> capabilities() { return capabilities; }
            @Override public Map<String, QueueGenerationLimit> generationLimits() { return Map.of(); }
            @Override public Map<String, Boolean> imageInput() { return Map.of(); }
            @Override public Optional<List<String>> backgroundSubmitters() { return Optional.empty(); }
        };
    }

    private static LiveConfig over(Path file, BrokerConfig startup) {
        LiveConfig config = new LiveConfig();
        config.startup = startup;
        config.path = file.toString();
        return config;
    }

    private static LiveConfig over(Path file) {
        return over(file, startupCopy(Map.of(), Optional.of(List.of("192.168.5.99"))));
    }

    /** Writes the file and gives it a modification time later than any earlier write, as a real edit would. */
    private static void write(Path file, String text, int secondsAfterEpochTick) throws IOException {
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + secondsAfterEpochTick * 1000L));
    }

    @Test
    @DisplayName("reads the foreground-to-background slot ratio of an address, and none where it is not written")
    void parse_readsTheSlotRatio() {
        String text = """
                broker:
                  capabilities:
                    "172.19.17.182:8000":
                      max-concurrency: 64
                      foreground-background-slot-ratio: 2.5
                    "192.168.5.18:8000":
                      max-concurrency: 32
                """;
        LiveConfig.Snapshot snapshot = LiveConfig.parse(new StringReader(text), Optional.empty());

        assertEquals(OptionalInt.of(64), snapshot.capabilities().get("172.19.17.182:8000").maxConcurrency());
        assertEquals(OptionalDouble.of(2.5), snapshot.capabilities().get("172.19.17.182:8000").foregroundBackgroundSlotRatio());
        assertEquals(OptionalDouble.empty(), snapshot.capabilities().get("192.168.5.18:8000").foregroundBackgroundSlotRatio());
    }

    @Test
    @DisplayName("reads every table of the file")
    void parse_readsEveryTable() {
        LiveConfig.Snapshot snapshot = LiveConfig.parse(new StringReader(SAMPLE), Optional.empty());

        assertEquals(OptionalInt.of(1), snapshot.capabilities().get("192.168.5.13:8000").maxConcurrency());
        assertEquals(OptionalInt.of(4), snapshot.capabilities().get("192.168.5.16:8000").maxConcurrency());
        assertEquals(OptionalInt.of(1048576), snapshot.capabilities().get("192.168.5.16:8000").maxContextLength());
        assertEquals(Optional.of(true), snapshot.capabilities().get("192.168.5.16:8000").thinkingModeSupported());
        assertEquals(OptionalInt.of(32768), snapshot.generationLimits().get("chat-qwen3.8-flash-next").maxTokens());
        assertEquals(OptionalDouble.of(0.3), snapshot.generationLimits().get("chat-qwen3.8-flash-next").frequencyPenalty());
        assertEquals(Map.of("chat-google-gemma-4-26B-A4B-it", true, "chat-text-only", false), snapshot.imageInput());
        assertEquals(java.util.Set.of("rst-frame-import", "nightly-batch"), snapshot.backgroundSubmitters());
        assertEquals(Optional.empty(), snapshot.nodes());
    }

    @Test
    @DisplayName("a file with none of the tables is a valid, empty set of tables")
    void parse_noTables_isEmpty() {
        LiveConfig.Snapshot snapshot = LiveConfig.parse(new StringReader("broker:\n  history: {}\n"), Optional.empty());
        assertTrue(snapshot.capabilities().isEmpty());
        assertTrue(snapshot.generationLimits().isEmpty());
        assertTrue(snapshot.imageInput().isEmpty());
        assertTrue(snapshot.backgroundSubmitters().isEmpty());
        assertTrue(LiveConfig.parse(new StringReader(""), Optional.empty()).capabilities().isEmpty());
    }

    @Test
    @DisplayName("nodes come from the file as a list or a comma-separated string, else from the startup value")
    void parse_nodes() {
        assertEquals(Optional.of(List.of("192.168.5.16", "192.168.5.0/26")),
                LiveConfig.parse(new StringReader("broker:\n  nodes:\n    - 192.168.5.16\n    - 192.168.5.0/26\n"), Optional.empty()).nodes());
        assertEquals(Optional.of(List.of("192.168.5.16", "192.168.5.17")),
                LiveConfig.parse(new StringReader("broker:\n  nodes: \"192.168.5.16, 192.168.5.17\"\n"), Optional.empty()).nodes());
        assertEquals(Optional.of(List.of("10.0.0.1")),
                LiveConfig.parse(new StringReader("broker:\n  capabilities: {}\n"), Optional.of(List.of("10.0.0.1"))).nodes());
    }

    @Test
    @DisplayName("a concurrency that is not a number, or an image-input that is not true or false, is rejected")
    void parse_wrongShapes_areRejected() {
        String badConcurrency = "broker:\n  capabilities:\n    \"192.168.5.16:8000\":\n      max-concurrency: many\n";
        String badImage = "broker:\n  image-input:\n    \"q\": maybe\n";
        assertThrows(IllegalArgumentException.class, () -> LiveConfig.parse(new StringReader(badConcurrency), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> LiveConfig.parse(new StringReader(badImage), Optional.empty()));
    }

    @Test
    @DisplayName("an edit to the file is seen by the next call, with no restart")
    void current_seesEditsToTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yaml");
        write(file, SAMPLE, 0);
        LiveConfig config = over(file);
        assertEquals(OptionalInt.of(4), config.capabilities().get("192.168.5.16:8000").maxConcurrency());

        // The operator deletes the line left over from the previous model on this node.
        write(file, "broker:\n  capabilities:\n    \"192.168.5.13:8000\":\n      max-concurrency: 1\n", 10);

        assertFalse(config.capabilities().containsKey("192.168.5.16:8000"));
        assertTrue(config.capabilities().containsKey("192.168.5.13:8000"));
    }

    @Test
    @DisplayName("a file that becomes unreadable keeps the last good tables")
    void current_brokenFile_keepsLastGood(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yaml");
        write(file, SAMPLE, 0);
        LiveConfig config = over(file);
        config.current();

        write(file, "broker:\n  capabilities: [this, is, not, a, map]\n", 10);

        assertEquals(OptionalInt.of(4), config.capabilities().get("192.168.5.16:8000").maxConcurrency(),
                "a half-written file must not reset every node to its default");
    }

    @Test
    @DisplayName("without the file, the startup copy is used")
    void current_noFile_fallsBackToStartupCopy(@TempDir Path dir) {
        BrokerConfig.EndpointCapability declared = LiveConfig.parse(new StringReader(SAMPLE), Optional.empty())
                .capabilities().get("192.168.5.13:8000");
        LiveConfig config = over(dir.resolve("absent.yaml"),
                startupCopy(Map.of("192.168.5.99:8000", declared), Optional.of(List.of("192.168.5.99"))));

        assertEquals(Map.of("192.168.5.99:8000", declared), config.capabilities());
        assertEquals(List.of("192.168.5.99"), config.nodes());
    }

    @Test
    @DisplayName("a queue's own generation limit wins; a queue without one gets the * entry; none without both")
    void generationLimitFor_ownThenDefault(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yaml");
        write(file, SAMPLE, 0);
        LiveConfig config = over(file);

        assertEquals(OptionalInt.of(32768), config.generationLimitFor("chat-qwen3.8-flash-next").maxTokens());
        assertEquals(OptionalInt.of(8192), config.generationLimitFor("chat-some-other-model").maxTokens());

        write(file, "broker:\n  capabilities: {}\n", 10);
        assertNull(config.generationLimitFor("chat-some-other-model"));
    }

    @Test
    @DisplayName("a submitter under background-submitters runs at background whatever it asked for")
    void priorityFor_listedSubmitterIsDemoted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yaml");
        write(file, SAMPLE, 0);
        LiveConfig config = over(file);

        assertEquals(Priority.BACKGROUND, config.priorityFor("rst-frame-import", Priority.FOREGROUND));
        assertEquals(Priority.BACKGROUND, config.priorityFor("rst-frame-import", Priority.BACKGROUND));
        assertEquals(Priority.FOREGROUND, config.priorityFor("chat-ui", Priority.FOREGROUND),
                "a submitter that is not listed keeps the priority it asked for");
        assertEquals(Priority.FOREGROUND, config.priorityFor("unknown", Priority.FOREGROUND));
        assertEquals(Priority.FOREGROUND, config.priorityFor(null, Priority.FOREGROUND));
    }

    @Test
    @DisplayName("adding a name to background-submitters takes effect on the next call")
    void priorityFor_followsEditsToTheList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("application.yaml");
        write(file, "broker:\n  capabilities: {}\n", 0);
        LiveConfig config = over(file);
        assertEquals(Priority.FOREGROUND, config.priorityFor("rst-frame-import", Priority.FOREGROUND));

        write(file, "broker:\n  background-submitters:\n    - rst-frame-import\n", 10);

        assertEquals(Priority.BACKGROUND, config.priorityFor("rst-frame-import", Priority.FOREGROUND));
    }
}
