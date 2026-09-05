package com.scivicslab.gpubroker.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("S_history")
@DisplayName("StatusHistoryStoreProducer — where the history file goes")
class StatusHistoryStoreProducerTest {

    @Test
    void unset_fallsBackToTheUserHomeDirectory() {
        Path file = StatusHistoryStoreProducer.historyFile(Optional.empty());

        assertEquals(Path.of(System.getProperty("user.home"),
                StatusHistoryStoreProducer.DEFAULT_RELATIVE_PATH), file);
    }

    /**
     * The home directory must already be resolved. Writing {@code ${user.home}/...} as the
     * annotation's defaultValue left it unexpanded, and the broker wrote its history into a
     * directory literally named {@code ${user.home}} under wherever it was started.
     */
    @Test
    void theDefaultPathIsAbsoluteAndContainsNoUnexpandedExpression() {
        Path file = StatusHistoryStoreProducer.historyFile(Optional.empty());

        assertTrue(file.isAbsolute());
        assertFalse(file.toString().contains("${"));
    }

    @Test
    void configured_isUsedAsGiven() {
        Path file = StatusHistoryStoreProducer.historyFile(Optional.of("/tmp/history.jsonl"));

        assertEquals(Path.of("/tmp/history.jsonl"), file);
    }

    /** An empty value is the same as not setting it — see {@code QuarkusConfigProperty} pitfalls. */
    @Test
    void blank_isTreatedAsUnset() {
        Path file = StatusHistoryStoreProducer.historyFile(Optional.of("  "));

        assertEquals(Path.of(System.getProperty("user.home"),
                StatusHistoryStoreProducer.DEFAULT_RELATIVE_PATH), file);
    }
}
