package com.scivicslab.gpubroker.capacity;

import java.util.Optional;

/**
 * What a vLLM server says about itself in its Prometheus {@code /metrics}: when this run of the
 * server started, how many requests it is computing now, and how many it keeps waiting because it
 * is full. The start time tells one run of a server from the next one at the same address: a
 * capacity declared for, or observed on, an earlier run says nothing about this one.
 *
 * <p>A server that does not expose these lines (TensorFold, Strata) has no {@code ServerRun}.
 */
public record ServerRun(double startTime, int running, int waitingForCapacity) {

    private static final String START = "process_start_time_seconds";
    private static final String RUNNING = "vllm:num_requests_running";
    private static final String WAITING_BY_REASON = "vllm:num_requests_waiting_by_reason";

    /**
     * Reads a {@code ServerRun} out of the text of {@code /metrics}, or empty when the text lacks
     * the start time or the running count. The waiting count is 0 when the server does not break
     * its waiting requests down by reason.
     */
    public static Optional<ServerRun> parse(String metrics) {
        Double start = null;
        Double running = null;
        double waiting = 0;
        for (String line : metrics.split("\n")) {
            if (line.startsWith("#")) {
                continue;
            }
            if (line.startsWith(START + " ")) {
                start = valueOf(line);
            } else if (line.startsWith(RUNNING + "{") || line.startsWith(RUNNING + " ")) {
                Double v = valueOf(line);
                running = v == null ? running : (running == null ? v : running + v);
            } else if (line.startsWith(WAITING_BY_REASON + "{") && line.contains("reason=\"capacity\"")) {
                Double v = valueOf(line);
                waiting += v == null ? 0 : v;
            }
        }
        if (start == null || running == null) {
            return Optional.empty();
        }
        return Optional.of(new ServerRun(start, (int) Math.round(running), (int) Math.round(waiting)));
    }

    /** The sample value: the last field of the line. Null when it is not a number. */
    private static Double valueOf(String line) {
        String[] fields = line.trim().split("\\s+");
        try {
            return Double.parseDouble(fields[fields.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether {@code other} is the same run of the server: the start times agree within a second. */
    public boolean sameRunAs(double otherStartTime) {
        return Math.abs(startTime - otherStartTime) < 1.0;
    }
}
