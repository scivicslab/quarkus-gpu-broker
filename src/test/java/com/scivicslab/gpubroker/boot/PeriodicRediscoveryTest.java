package com.scivicslab.gpubroker.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.EndpointProbe;

/**
 * The rules a round of probing implies for the registry, and the bookkeeping that lets a queue
 * stop being advertised once nothing serves it. Both are plain objects: {@link ReconcilePlan}
 * compares two maps and {@link JobQueueRegistryState} is the registry's own state, so neither
 * needs an {@code ActorSystem}. Carrying a plan out (creating and closing actors) is
 * {@code JobQueueRegistry}'s job and is exercised end to end by {@code StartupDiscoveryE2E}.
 */
@Tag("PeriodicRediscovery_260923_oo01")
@DisplayName("再起動なしの再発見 — 走査結果と登録簿の突き合わせ")
class PeriodicRediscoveryTest {

    /** Stands in for a real probe: nothing here reaches the network. */
    private static final class StubProbe implements EndpointProbe {
        @Override public int conventionalPort() { return 8000; }
        @Override public String probePath() { return "/v1/models"; }
        @Override public String requestPath() { return "/v1/chat/completions"; }
        @Override public Optional<String> deriveQueueName(String body) { return Optional.empty(); }
        @Override public int defaultMaxConcurrency() { return 1; }
    }

    private static final EndpointProbe PROBE = new StubProbe();

    private static EndpointSurveyor.Found found(String address, String queueName) {
        return new EndpointSurveyor.Found(PROBE, new EndpointInfo(address, queueName, queueName, 1, 0));
    }

    @Test
    @DisplayName("応答したが未登録のアドレスは、登録対象になる")
    void of_respondingUnregisteredAddress_isRegistered() {
        List<ReconcilePlan.Change> changes = ReconcilePlan.of(
                Map.of(), List.of(found("192.168.5.19:8000", "chat-qwen3.8-flash-next")));

        assertEquals(1, changes.size());
        assertEquals("192.168.5.19:8000", changes.get(0).found().info().address());
        assertNull(changes.get(0).leavingQueue(), "nowhere to leave from");
    }

    @Test
    @DisplayName("別のモデルを名乗り始めたアドレスは、古い待ち行列から移される")
    void of_addressNowServingAnotherModel_movesQueue() {
        Map<String, String> registered = Map.of("192.168.5.19:8000", "chat-nvidia-Cosmos3-Nano");

        List<ReconcilePlan.Change> changes = ReconcilePlan.of(
                registered, List.of(found("192.168.5.19:8000", "chat-qwen3.8-flash-next")));

        assertEquals(1, changes.size());
        assertEquals("chat-nvidia-Cosmos3-Nano", changes.get(0).leavingQueue());
        assertEquals("chat-qwen3.8-flash-next", changes.get(0).found().info().queueName());
    }

    @Test
    @DisplayName("同じ待ち行列に登録済みのアドレスは、何も起こさない")
    void of_alreadyRegisteredInSameQueue_producesNoChange() {
        Map<String, String> registered = Map.of("192.168.5.18:8000", "chat-qwen3.8-flash-next");

        List<ReconcilePlan.Change> changes = ReconcilePlan.of(
                registered, List.of(found("192.168.5.18:8000", "chat-qwen3.8-flash-next")));

        assertTrue(changes.isEmpty());
    }

    @Test
    @DisplayName("応答しなかった登録済みアドレスは、そのまま残る")
    void of_registeredAddressThatAnsweredNothing_isLeftAlone() {
        Map<String, String> registered = Map.of(
                "192.168.5.23:8000", "chat-Qwen-Qwen3.8-27B",
                "192.168.5.18:8000", "chat-qwen3.8-flash-next");

        // 5.23 restarted and answered nothing this round; only 5.18 is in the survey.
        List<ReconcilePlan.Change> changes = ReconcilePlan.of(
                registered, List.of(found("192.168.5.18:8000", "chat-qwen3.8-flash-next")));

        assertTrue(changes.isEmpty(), "a silent endpoint must not be torn out of its queue");
    }

    @Test
    @DisplayName("最後の待受口を失った待ち行列は、広告からも名前解決からも消える")
    void removeQueue_afterLastEndpointLeaves_stopsAdvertisingAndResolving() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.registerQueue("chat-nvidia-Cosmos3-Nano", () -> null);
        state.putDisplayName("chat-nvidia-Cosmos3-Nano", "nvidia/Cosmos3-Nano");
        state.putEndpoint("192.168.5.19:8000", "chat-nvidia-Cosmos3-Nano");

        assertTrue(state.hasEndpoints("chat-nvidia-Cosmos3-Nano"));

        state.removeEndpoint("192.168.5.19:8000");
        assertFalse(state.hasEndpoints("chat-nvidia-Cosmos3-Nano"));

        state.removeQueue("chat-nvidia-Cosmos3-Nano");
        assertFalse(state.displayNames().containsKey("chat-nvidia-Cosmos3-Nano"),
                "GET /v1/models must stop offering a model nothing serves");
        assertNull(state.get("chat-nvidia-Cosmos3-Nano"),
                "a request naming it must fall through to the unknown-queue 404");
    }

    @Test
    @DisplayName("無応答が閾値に達するまでは、stale として報告されない")
    void staleAddresses_beforeThreshold_reportsNothing() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.putEndpoint("192.168.5.17:8001", "marker-ocr");

        for (int round = 1; round < JobQueueRegistryState.MISSES_BEFORE_STALE; round++) {
            assertTrue(state.staleAddresses(Set.of()).isEmpty(),
                    "round " + round + " must not yet report the address as stale");
        }
    }

    @Test
    @DisplayName("無応答が連続して閾値に達したアドレスは、stale として報告される")
    void staleAddresses_atThreshold_reportsTheAddress() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.putEndpoint("192.168.5.17:8001", "marker-ocr");

        List<String> stale = List.of();
        for (int round = 1; round <= JobQueueRegistryState.MISSES_BEFORE_STALE; round++) {
            stale = state.staleAddresses(Set.of());
        }

        assertEquals(List.of("192.168.5.17:8001"), stale,
                "the " + JobQueueRegistryState.MISSES_BEFORE_STALE + "th consecutive miss must report it");
    }

    @Test
    @DisplayName("途中で一度でも応答すれば、無応答の連続回数は0に戻る")
    void staleAddresses_oneAnswerInBetween_resetsTheCount() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.putEndpoint("192.168.5.17:8001", "marker-ocr");

        // Miss every round but the last, up to one short of the threshold, then answer once --
        // a busy endpoint (measured: Marker's own GET / took 8 s under four concurrent requests
        // against a 2 s probe timeout) regains its clean slate the moment it answers again.
        for (int round = 1; round < JobQueueRegistryState.MISSES_BEFORE_STALE; round++) {
            state.staleAddresses(Set.of());
        }
        state.staleAddresses(Set.of("192.168.5.17:8001"));

        for (int round = 1; round < JobQueueRegistryState.MISSES_BEFORE_STALE; round++) {
            assertTrue(state.staleAddresses(Set.of()).isEmpty(),
                    "the reset must buy another " + JobQueueRegistryState.MISSES_BEFORE_STALE
                            + " rounds of grace, round " + round + " must not yet report it");
        }
    }

    @Test
    @DisplayName("他のアドレスが残っている待ち行列は、広告され続ける")
    void hasEndpoints_whenAnotherAddressRemains_staysTrue() {
        JobQueueRegistryState state = new JobQueueRegistryState();
        state.putEndpoint("192.168.5.16:8000", "chat-google-gemma-4-26B-A4B-it");
        state.putEndpoint("192.168.5.17:8000", "chat-google-gemma-4-26B-A4B-it");

        state.removeEndpoint("192.168.5.16:8000");

        assertTrue(state.hasEndpoints("chat-google-gemma-4-26B-A4B-it"));
        assertEquals(Map.of("192.168.5.17:8000", "chat-google-gemma-4-26B-A4B-it"),
                state.endpointQueues());
    }
}
