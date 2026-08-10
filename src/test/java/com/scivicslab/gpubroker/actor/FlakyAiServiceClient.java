package com.scivicslab.gpubroker.actor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.scivicslab.gpubroker.llm.AiServiceCallException;
import com.scivicslab.gpubroker.llm.AiServiceClient;
import com.scivicslab.gpubroker.model.Job;

/**
 * Test stub for {@link AiServiceClient}: addresses marked {@link
 * #markUnhealthy} always raise {@link AiServiceCallException}; every other
 * address succeeds immediately (synchronously, no blocking) — unlike {@link
 * LatchAiServiceClient}, this one needs no in-flight choreography, only a
 * deterministic pass/fail per address.
 */
final class FlakyAiServiceClient implements AiServiceClient {

    private final Set<String> unhealthyAddresses = ConcurrentHashMap.newKeySet();

    void markUnhealthy(String address) {
        unhealthyAddresses.add(address);
    }

    @Override
    public void send(String address, String path, Job job) {
        if (unhealthyAddresses.contains(address)) {
            throw new AiServiceCallException("stub: " + address + " is unhealthy");
        }
        job.responseSink().start("text/plain");
        job.responseSink().emit(LatchAiServiceClient.label(job).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        job.responseSink().complete();
    }
}
