package com.scivicslab.gpubroker.e2e;

/**
 * Entry point for quarkus-gpu-broker E2E tests.
 *
 * <p>Runs all E2E scenarios in sequence against an already-running gpu-broker
 * instance (see {@code E2ETestPlan_260810_oo01}). Any failure throws an
 * exception and exits with a non-zero code.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>quarkus-gpu-broker deployed and running (e.g. via AI-workspace) at
 *       -De2e.base.url (default: http://localhost:28003)</li>
 *   <li>reachable from this host to the real W206 cluster (192.168.5.0/26) —
 *       the config/application.yaml override AI-workspace supplies
 *       (see CapabilityConfig_260810_oo01) must already be in effect on the
 *       running instance</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.gpubroker.e2e.GpuBrokerE2ERunner \
 *     -Dexec.classpathScope=test \
 *     -De2e.base.url=http://localhost:28003
 * </pre>
 */
public class GpuBrokerE2ERunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== quarkus-gpu-broker E2E Tests ===");
        new StartupDiscoveryE2E().run();
        new EmbeddingRoundTripE2E().run();
        new StreamingRoundTripE2E().run();
        new PriorityOrderingE2E().run();        // up to ~4 minutes (see its own Javadoc)
        new CappedConcurrencyLoadE2E().run();
        new ForegroundReservationE2E().run();   // ~3 minutes
        new SubmitOrderPreservedE2E().run();    // ~3.5 minutes
        // JobResultTtlE2E (~70 minutes) is NOT run here — see its own Javadoc.
        // ReservationStarvationBugE2E is NOT run here either: it now passes (the bug is
        // fixed), but still takes ~3.5 minutes — see JobQueueReservationStarvationBug_260819_oo01.
        System.out.println("=== All E2E tests PASSED ===");
    }
}
