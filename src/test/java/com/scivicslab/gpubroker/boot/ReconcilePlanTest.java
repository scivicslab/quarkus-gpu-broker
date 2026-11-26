package com.scivicslab.gpubroker.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.config.EmbeddingProbe;
import com.scivicslab.gpubroker.config.EndpointInfo;
import com.scivicslab.gpubroker.config.VllmChatProbe;

class ReconcilePlanTest {

    private static final String ADDRESS = "192.168.5.13:11435";
    private static final String CHAT = "chat-hf.co-unsloth-Inkling-2-Mini-GGUF-UD-Q4_K_XL";

    private static EndpointSurveyor.Found chat() {
        return new EndpointSurveyor.Found(new VllmChatProbe(), new EndpointInfo(ADDRESS, CHAT, CHAT, 2, 0));
    }

    private static EndpointSurveyor.Found embedding() {
        return new EndpointSurveyor.Found(new EmbeddingProbe(),
                new EndpointInfo(ADDRESS, "embedding-e5large", "embedding-e5large", 8, 0));
    }

    /** An address two kinds claim must stay where it is, whichever order the kinds answer in. */
    @Test
    void anAddressClaimedByTwoKindsStaysInTheQueueItIsRegisteredIn() {
        assertTrue(ReconcilePlan.of(Map.of(ADDRESS, CHAT), List.of(chat(), embedding())).isEmpty());
        assertTrue(ReconcilePlan.of(Map.of(ADDRESS, CHAT), List.of(embedding(), chat())).isEmpty());
        assertTrue(ReconcilePlan.of(Map.of(ADDRESS, "embedding-e5large"), List.of(chat(), embedding())).isEmpty());
    }

    @Test
    void anAddressNobodyRegisteredYetIsRegisteredOnceUnderTheFirstClaim() {
        List<ReconcilePlan.Change> changes = ReconcilePlan.of(Map.of(), List.of(chat(), embedding()));
        assertEquals(1, changes.size());
        assertEquals(CHAT, changes.get(0).found().info().queueName());
    }

    @Test
    void anAddressWhoseOnlyClaimChangedMoves() {
        List<ReconcilePlan.Change> changes = ReconcilePlan.of(Map.of(ADDRESS, "chat-old"), List.of(chat()));
        assertEquals(1, changes.size());
        assertEquals("chat-old", changes.get(0).leavingQueue());
    }
}
