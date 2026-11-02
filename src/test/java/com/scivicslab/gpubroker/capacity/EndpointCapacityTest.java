package com.scivicslab.gpubroker.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The slots of an endpoint follow what its server really computes (see {@code
 * DeclaredEndpointConcurrency_261103_oo01}). The metrics below are the lines 192.168.5.18:8000
 * served on 2026-11-03, when it ran with --max-num-seqs 8 and the broker sent it 32 jobs.
 */
class EndpointCapacityTest {

    private static final String ADDRESS = "192.168.5.18:8000";
    private static final double START = 1.79216136092e+09;

    private static final String FULL_SERVER_METRICS = """
            # HELP process_start_time_seconds Start time of the process since unix epoch in seconds.
            # TYPE process_start_time_seconds gauge
            process_start_time_seconds 1.79216136092e+09
            vllm:num_requests_running{engine="0",model_name="google/gemma-4-26B-A4B-it"} 8.0
            vllm:num_requests_waiting{engine="0",model_name="google/gemma-4-26B-A4B-it"} 24.0
            vllm:num_requests_waiting_by_reason{engine="0",model_name="google/gemma-4-26B-A4B-it",reason="capacity"} 24.0
            vllm:num_requests_waiting_by_reason{engine="0",model_name="google/gemma-4-26B-A4B-it",reason="deferred"} 0.0
            """;

    @Test
    void readsTheStartTimeTheRunningAndTheWaitingForCapacity() {
        ServerRun run = ServerRun.parse(FULL_SERVER_METRICS).orElseThrow();
        assertEquals(START, run.startTime(), 0.01);
        assertEquals(8, run.running());
        assertEquals(24, run.waitingForCapacity());
    }

    @Test
    void aServerWithoutVllmLinesHasNoRun() {
        assertTrue(ServerRun.parse("process_start_time_seconds 1.0\nother_metric 3\n").isEmpty());
    }

    @Test
    void aFullServerLowersItsSlotsToWhatItComputes() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        Optional<ServerRun> run = ServerRun.parse(FULL_SERVER_METRICS);
        assertEquals(8, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(), run));
        // the next survey: the broker now sends 8, nothing waits, the slots stay 8
        assertEquals(8, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(),
                Optional.of(new ServerRun(START, 8, 0))));
    }

    @Test
    void aRestartedServerForgetsWhatWasObservedOnItsLastRun() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(), ServerRun.parse(FULL_SERVER_METRICS));
        assertEquals(32, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(),
                Optional.of(new ServerRun(START + 3600, 0, 0))));
    }

    @Test
    void aDeclarationForTheRunningServerGivesItsSlots() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        capacity.declare(ADDRESS, new EndpointCapacity.Declaration(48, START));
        assertEquals(48, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(),
                Optional.of(new ServerRun(START, 10, 0))));
    }

    @Test
    void aDeclarationForAnEarlierRunIsNotUsed() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        capacity.declare(ADDRESS, new EndpointCapacity.Declaration(48, START - 86400));
        assertEquals(32, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(),
                Optional.of(new ServerRun(START, 10, 0))));
    }

    @Test
    void aServerThatComputesLessThanItsDeclarationGetsWhatItComputes() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        capacity.declare(ADDRESS, new EndpointCapacity.Declaration(32, START));
        assertEquals(8, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(), ServerRun.parse(FULL_SERVER_METRICS)));
    }

    @Test
    void theOperatorsMaxConcurrencyComesFirst() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        capacity.declare(ADDRESS, new EndpointCapacity.Declaration(48, START));
        assertEquals(4, capacity.slotsFor(ADDRESS, 32, OptionalInt.of(4), ServerRun.parse(FULL_SERVER_METRICS)));
    }

    @Test
    void aServerThatSaysNothingGetsTheKindDefault() {
        EndpointCapacity capacity = new EndpointCapacity(null);
        assertEquals(32, capacity.slotsFor(ADDRESS, 32, OptionalInt.empty(), Optional.empty()));
    }

    @Test
    void declarationsSurviveARestartOfTheBroker(@TempDir Path dir) {
        new EndpointCapacity(dir).declare(ADDRESS, new EndpointCapacity.Declaration(48, START));
        EndpointCapacity afterRestart = new EndpointCapacity(dir);
        assertEquals(48, afterRestart.slotsFor(ADDRESS, 32, OptionalInt.empty(),
                Optional.of(new ServerRun(START, 0, 0))));
    }
}
