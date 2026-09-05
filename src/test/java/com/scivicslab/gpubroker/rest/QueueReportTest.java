package com.scivicslab.gpubroker.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.scivicslab.gpubroker.history.ProbeObservation;
import com.scivicslab.gpubroker.history.StatusHistoryStore;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

@DisplayName("QueueReport — what GET /queues tells a program")
class QueueReportTest {

    private static final Instant NOON = Instant.parse("2026-09-05T12:00:00Z");

    private static QueueStatus status(String queueName, List<String> active, List<String> idle, int pending) {
        return new QueueStatus(queueName, new QueueSnapshot(active, idle, pending, 0, 0));
    }

    @Test
    void countsSlotsAndJobsSeparately() {
        QueueReport report = QueueReport.of(
                status("q", List.of("a:1#0"), List.of("a:1#1", "a:1#2"), 5), new StatusHistoryStore(null));

        assertEquals("q", report.name());
        assertEquals(1, report.activeSlots());
        assertEquals(2, report.idleSlots());
        assertEquals(3, report.totalSlots());
        assertEquals(5, report.pendingJobs());
    }

    /** Several workers share one address, and the report names addresses, not workers. */
    @Test
    void oneEntryPerAddress() {
        QueueReport report = QueueReport.of(
                status("q", List.of("a:1#0"), List.of("a:1#1", "b:2#0"), 0), new StatusHistoryStore(null));

        assertEquals(List.of("a:1", "b:2"), report.endpoints().stream().map(EndpointReport::address).toList());
    }

    /** Before the first probe, the slot count alone decides — otherwise every queue reads unusable at startup. */
    @Test
    void withoutAnyProbe_isReadyWhenItHasSlots() {
        QueueReport report = QueueReport.of(
                status("q", List.of(), List.of("a:1#0"), 0), new StatusHistoryStore(null));

        assertTrue(report.ready());
        assertEquals("UNKNOWN", report.endpoints().get(0).health());
    }

    @Test
    void withoutAnySlot_isNotReady() {
        QueueReport report = QueueReport.of(status("q", List.of(), List.of(), 0), new StatusHistoryStore(null));

        assertFalse(report.ready());
        assertEquals(0, report.totalSlots());
    }

    /**
     * The point of the endpoint: a queue whose addresses stopped answering is not ready, even
     * though JobQueue still has them registered. That registration outliving the service is the
     * normal case — JobQueue only drops an address when a job happens to fail on it.
     */
    @Test
    void addressRegisteredButNotAnswering_isNotReady() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.record(NOON, List.of(new ProbeObservation("a:1", "q", false)), List.of());

        QueueReport report = QueueReport.of(status("q", List.of(), List.of("a:1#0"), 0), history);

        assertFalse(report.ready());
        assertEquals("DOWN", report.endpoints().get(0).health());
    }

    @Test
    void oneAddressAnswering_isReadyEvenIfAnotherIsDown() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.record(NOON, List.of(new ProbeObservation("a:1", "q", true),
                new ProbeObservation("b:2", "q", false)), List.of());

        QueueReport report = QueueReport.of(status("q", List.of(), List.of("a:1#0", "b:2#0"), 0), history);

        assertTrue(report.ready());
    }

    /** Answering some but not all probes of a window is reported as such, not flattened to up or down. */
    @Test
    void partialAnswers_areReportedAsPartial() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.record(NOON, List.of(new ProbeObservation("a:1", "q", true)), List.of());
        history.record(NOON.plus(Duration.ofMinutes(1)),
                List.of(new ProbeObservation("a:1", "q", false)), List.of());

        QueueReport report = QueueReport.of(status("q", List.of(), List.of("a:1#0"), 0), history);

        assertEquals("PARTIAL", report.endpoints().get(0).health());
        assertEquals(1, report.endpoints().get(0).probeOk());
        assertEquals(2, report.endpoints().get(0).probeTotal());
        assertTrue(report.ready(), "answered at least one probe, so work can still be sent");
    }

    @Test
    void reportsThroughputOfTheLastHour() {
        StatusHistoryStore history = new StatusHistoryStore(null);
        history.record(NOON, List.of(), List.of(status("q", List.of(), List.of("a:1#0"), 0)));
        history.record(NOON.plus(Duration.ofMinutes(1)), List.of(),
                List.of(new QueueStatus("q", new QueueSnapshot(List.of(), List.of("a:1#0"), 0, 7, 0))));

        QueueReport report = QueueReport.of(status("q", List.of(), List.of("a:1#0"), 0), history);

        assertEquals(7, report.completedLastHour());
    }
}
