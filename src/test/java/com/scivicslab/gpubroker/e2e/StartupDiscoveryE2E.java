package com.scivicslab.gpubroker.e2e;

/**
 * Verifies the already-running broker discovered the real W206 cluster
 * at startup (scenarios 1, 12 of {@code E2ETestPlan_260810_oo01}).
 */
class StartupDiscoveryE2E extends GpuBrokerE2EBase {

    public static void main(String[] args) throws Exception {
        new StartupDiscoveryE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- StartupDiscoveryE2E ---");
        String status = fetchStatus();

        require(status.contains("embedding-e5large"), "expected embedding-e5large queue: " + status);
        require(status.contains("vllm-Qwen2.5-14B-Instruct-AWQ"), "expected the capped Qwen queue: " + status);
        // google/gemma-4-26B-A4B-it contains '/' — queueName must be sanitized, not just present.
        require(status.contains("vllm-google-gemma-4-26B-A4B-it"), "expected sanitized gemma-4 queueName: " + status);
        require(!status.contains("vllm-google/gemma"), "queueName must not retain '/': " + status);

        LOG.info("Discovered queues confirmed on: " + BASE_URL);
        System.out.println("StartupDiscoveryE2E: PASSED");
    }
}
