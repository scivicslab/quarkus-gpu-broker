package com.scivicslab.gpubroker.rest;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.history.EndpointBucket;
import com.scivicslab.gpubroker.history.QueueBucket;
import com.scivicslab.gpubroker.history.StatusHistoryStore;
import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * Builds the status page HTML: one card per queue, each card holding the
 * current reading, the last 24 hours of congestion, and the last 24 hours of
 * liveness per address (see {@code StatusHistory_260905_oo01}).
 *
 * <p>No template engine and no charting library — the charts are inline SVG
 * built from the same {@code StatusHistoryStore} buckets, so the page stays
 * one string built by one class, as it was when it showed only the current
 * reading ({@code QueueSnapshotStatus_260810_oo01}).
 *
 * <p>Alongside each address, shows any operator-declared capability
 * ({@code max-context-length}, {@code thinking-mode-supported}, {@code
 * tool-calling-supported} from {@code broker.capabilities} — see {@code
 * CapabilityConfig_260810_oo01}) read directly from {@link BrokerConfig},
 * never through {@code EndpointInfo}/the Actor tree — these values are
 * declared, not measured, and are labeled "declared" so they are not
 * mistaken for live data.
 */
final class StatusPageRenderer {

    /** Width of both history charts, in SVG user units — 144 buckets at 5 units each. */
    private static final int CHART_WIDTH = StatusHistoryStore.BUCKETS_PER_DAY * 5;
    private static final int CONGESTION_HEIGHT = 90;
    private static final int BAND_HEIGHT = 14;

    private StatusPageRenderer() {
    }

    static String render(List<QueueStatus> statuses, Map<String, BrokerConfig.EndpointCapability> capabilities,
                         StatusHistoryStore history) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"refresh\" content=\"10\">")
                .append("<title>gpu-broker status</title>")
                .append(style())
                .append("</head><body><header><h1>gpu-broker</h1>")
                .append("<p class=\"sub\">liveness and congestion, last 24 hours &middot; refreshes every 10s</p>")
                .append("</header><main>");

        if (statuses.isEmpty()) {
            html.append("<p class=\"empty\">No queues discovered yet.</p>");
        }
        for (QueueStatus status : statuses) {
            html.append(renderCard(status, capabilities, history));
        }

        html.append("</main></body></html>");
        return html.toString();
    }

    private static String style() {
        return "<style>"
                + ":root{--ink:#1c2530;--muted:#6b7684;--line:#e2e6ea;--bg:#f5f7f9;--card:#fff;"
                + "--active:#d05a4e;--pending:#3f7cac;--idle:#5aa469;--down:#c7cdd4}"
                + "*{box-sizing:border-box}"
                + "body{margin:0;padding:0 0 3rem;background:var(--bg);color:var(--ink);"
                + "font-family:system-ui,-apple-system,'Segoe UI',sans-serif;font-size:14px;line-height:1.5}"
                + "header{padding:1.6rem 2rem 1rem}"
                + "h1{margin:0;font-size:1.3rem;font-weight:600;letter-spacing:-0.01em}"
                + ".sub{margin:0.2rem 0 0;color:var(--muted);font-size:0.85rem}"
                + "main{padding:0 2rem;display:flex;flex-direction:column;gap:1rem}"
                + ".empty{color:var(--muted)}"
                + ".card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:1.1rem 1.3rem}"
                + ".head{display:flex;align-items:baseline;gap:0.9rem;flex-wrap:wrap}"
                + ".name{font-size:1.05rem;font-weight:600}"
                + ".metrics{display:flex;gap:1.1rem;margin-left:auto;font-variant-numeric:tabular-nums;"
                + "font-size:0.85rem;color:var(--muted)}"
                + ".metrics b{font-weight:600;color:var(--ink)}"
                + ".swatch{display:inline-block;width:0.6em;height:0.6em;border-radius:2px;margin-right:0.35em}"
                + ".now{display:flex;height:0.65rem;margin:0.7rem 0 0.2rem;background:var(--bg);"
                + "border-radius:3px;overflow:hidden}"
                + ".now i{display:block}"
                + ".scale{display:flex;justify-content:space-between;color:var(--muted);font-size:0.72rem}"
                + "section{margin-top:1.1rem}"
                + "h2{margin:0 0 0.4rem;font-size:0.78rem;font-weight:600;text-transform:uppercase;"
                + "letter-spacing:0.06em;color:var(--muted)}"
                + "svg{display:block;width:100%;height:auto}"
                + ".bands{display:flex;flex-direction:column;gap:0.3rem}"
                + ".band{display:flex;align-items:center;gap:0.7rem}"
                + ".addr{width:16rem;flex:none;font-variant-numeric:tabular-nums;font-size:0.82rem;"
                + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + ".addr small{color:var(--muted)}"
                + ".band .strip{flex:1}"
                + "</style>";
    }

    private static String renderCard(QueueStatus status, Map<String, BrokerConfig.EndpointCapability> capabilities,
                                     StatusHistoryStore history) {
        QueueSnapshot s = status.snapshot();
        List<QueueBucket> buckets = history.queueHistory(status.queueName());
        long completedLastHour = history.completedLastHour(status.queueName());

        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card\"><div class=\"head\"><span class=\"name\">")
                .append(escape(status.queueName())).append("</span><span class=\"metrics\">")
                .append(metric("active", "--active", s.activeCount()))
                .append(metric("pending", "--pending", s.pendingCount()))
                .append(metric("idle", "--idle", s.idleCount()))
                .append("<span>wait <b>").append(estimatedWait(s.pendingCount(), completedLastHour)).append("</b></span>")
                .append("<span>done/h <b>").append(completedLastHour).append("</b></span>")
                .append("</span></div>");

        appendCurrentBar(card, s, peakOf(buckets, s));
        appendCongestionChart(card, buckets);
        appendLivenessBands(card, status, history, capabilities);

        card.append("</div>");
        return card.toString();
    }

    private static String metric(String label, String colorVar, int count) {
        return "<span><i class=\"swatch\" style=\"background:var(" + colorVar + ")\"></i>"
                + label + " <b>" + count + "</b></span>";
    }

    /**
     * The current reading as a bar whose full width is the highest total this queue reached in
     * the last 24 hours, so its length reads as a quantity rather than a ratio — see {@code
     * StatusHistory_260905_oo01} "なぜ棒の全幅を24時間の最大値に取るか".
     */
    private static void appendCurrentBar(StringBuilder card, QueueSnapshot s, double peak) {
        card.append("<div class=\"now\">");
        appendSegment(card, "--active", s.activeCount(), peak);
        appendSegment(card, "--pending", s.pendingCount(), peak);
        appendSegment(card, "--idle", s.idleCount(), peak);
        card.append("</div><div class=\"scale\"><span>0</span><span>peak 24h: ")
                .append(Math.round(peak)).append("</span></div>");
    }

    private static void appendSegment(StringBuilder card, String colorVar, int count, double peak) {
        if (count == 0) {
            return;
        }
        card.append("<i style=\"width:").append(percent(count / peak))
                .append("%;background:var(").append(colorVar).append(")\"></i>");
    }

    private static double peakOf(List<QueueBucket> buckets, QueueSnapshot now) {
        double peak = now.activeCount() + now.idleCount() + now.pendingCount();
        for (QueueBucket bucket : buckets) {
            peak = Math.max(peak, bucket.activeAverage() + bucket.idleAverage() + bucket.pendingAverage());
        }
        return Math.max(peak, 1.0);
    }

    /**
     * Active and pending stacked as filled areas over the last 24 hours, with completions per
     * bucket drawn as a line on top. Buckets are right-aligned: the newest is at the right edge,
     * and a history shorter than 24 hours leaves the left side empty.
     */
    private static void appendCongestionChart(StringBuilder card, List<QueueBucket> buckets) {
        card.append("<section><h2>congestion &mdash; 24h, 10 min per step</h2>");
        if (buckets.isEmpty()) {
            card.append("<p class=\"empty\">No history recorded yet.</p></section>");
            return;
        }
        double loadPeak = 1.0;
        long donePeak = 1;
        for (QueueBucket bucket : buckets) {
            loadPeak = Math.max(loadPeak, bucket.activeAverage() + bucket.pendingAverage());
            donePeak = Math.max(donePeak, bucket.completed());
        }

        card.append("<svg viewBox=\"0 0 ").append(CHART_WIDTH).append(" ").append(CONGESTION_HEIGHT)
                .append("\" preserveAspectRatio=\"none\" role=\"img\">");
        int offset = StatusHistoryStore.BUCKETS_PER_DAY - buckets.size();
        for (int i = 0; i < buckets.size(); i++) {
            QueueBucket bucket = buckets.get(i);
            double activeHeight = CONGESTION_HEIGHT * bucket.activeAverage() / loadPeak;
            double pendingHeight = CONGESTION_HEIGHT * bucket.pendingAverage() / loadPeak;
            int x = (offset + i) * 5;
            appendColumn(card, x, CONGESTION_HEIGHT - activeHeight, activeHeight, "--active");
            appendColumn(card, x, CONGESTION_HEIGHT - activeHeight - pendingHeight, pendingHeight, "--pending");
        }
        appendCompletionLine(card, buckets, offset, donePeak);
        card.append("</svg><div class=\"scale\"><span>24h ago</span><span>peak load ")
                .append(Math.round(loadPeak)).append(" &middot; peak done/10min ").append(donePeak)
                .append("</span><span>now</span></div></section>");
    }

    private static void appendColumn(StringBuilder card, int x, double y, double height, String colorVar) {
        if (height <= 0) {
            return;
        }
        card.append("<rect x=\"").append(x).append("\" y=\"").append(round(y))
                .append("\" width=\"5\" height=\"").append(round(height))
                .append("\" fill=\"var(").append(colorVar).append(")\"/>");
    }

    private static void appendCompletionLine(StringBuilder card, List<QueueBucket> buckets, int offset, long donePeak) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < buckets.size(); i++) {
            double y = CONGESTION_HEIGHT - (double) CONGESTION_HEIGHT * buckets.get(i).completed() / donePeak;
            points.append((offset + i) * 5 + 2).append(",").append(round(y)).append(" ");
        }
        card.append("<polyline points=\"").append(points.toString().strip())
                .append("\" fill=\"none\" stroke=\"var(--ink)\" stroke-width=\"1.2\"")
                .append(" stroke-linejoin=\"round\" vector-effect=\"non-scaling-stroke\"/>");
    }

    /** One row per address: 144 cells coloured by how many of that bucket's probes it answered. */
    private static void appendLivenessBands(StringBuilder card, QueueStatus status, StatusHistoryStore history,
                                            Map<String, BrokerConfig.EndpointCapability> capabilities) {
        List<String> addresses = addressesOf(status, history);
        card.append("<section><h2>liveness &mdash; 24h, probed every minute</h2>");
        if (addresses.isEmpty()) {
            card.append("<p class=\"empty\">No probe result recorded yet.</p></section>");
            return;
        }
        card.append("<div class=\"bands\">");
        for (String address : addresses) {
            card.append("<div class=\"band\"><span class=\"addr\">").append(escape(address));
            appendDeclaredCapability(card, capabilities.get(address));
            card.append("</span><span class=\"strip\">");
            appendBand(card, history.endpointHistory(address));
            card.append("</span></div>");
        }
        card.append("</div></section>");
    }

    /**
     * Every address this queue should show a row for: those the probe has observed, plus those
     * currently registered in {@code JobQueue}. The second source matters in the first minute
     * after startup, before any probe has run — without it the card would list no address at all,
     * which is a step back from the page this one replaces ({@code QueueSnapshotStatus_260810_oo01}).
     */
    private static List<String> addressesOf(QueueStatus status, StatusHistoryStore history) {
        SortedSet<String> addresses = new TreeSet<>(history.addressesOf(status.queueName()));
        for (String workerId : status.snapshot().activeEndpointIds()) {
            addresses.add(physicalAddress(workerId));
        }
        for (String workerId : status.snapshot().idleEndpointIds()) {
            addresses.add(physicalAddress(workerId));
        }
        return List.copyOf(addresses);
    }

    /** A worker's actor name is {@code address#slot}; several workers share one address. */
    private static String physicalAddress(String workerId) {
        int slot = workerId.lastIndexOf('#');
        return slot < 0 ? workerId : workerId.substring(0, slot);
    }

    private static void appendBand(StringBuilder card, List<EndpointBucket> buckets) {
        card.append("<svg viewBox=\"0 0 ").append(CHART_WIDTH).append(" ").append(BAND_HEIGHT)
                .append("\" preserveAspectRatio=\"none\" role=\"img\">")
                .append("<rect x=\"0\" y=\"0\" width=\"").append(CHART_WIDTH).append("\" height=\"")
                .append(BAND_HEIGHT).append("\" fill=\"var(--bg)\"/>");
        int offset = StatusHistoryStore.BUCKETS_PER_DAY - buckets.size();
        for (int i = 0; i < buckets.size(); i++) {
            card.append("<rect x=\"").append((offset + i) * 5).append("\" y=\"0\" width=\"5\" height=\"")
                    .append(BAND_HEIGHT).append("\" fill=\"").append(healthColor(buckets.get(i))).append("\"/>");
        }
        card.append("</svg>");
    }

    private static String healthColor(EndpointBucket bucket) {
        return switch (bucket.health()) {
            case UP -> "var(--idle)";
            case PARTIAL -> "var(--active)";
            case DOWN -> "var(--down)";
        };
    }

    private static void appendDeclaredCapability(StringBuilder card, BrokerConfig.EndpointCapability capability) {
        if (capability == null) {
            return;
        }
        StringBuilder declared = new StringBuilder();
        capability.maxContextLength().ifPresent(v -> appendField(declared, "context " + v));
        capability.thinkingModeSupported().ifPresent(v -> appendField(declared, "thinking " + v));
        capability.toolCallingSupported().ifPresent(v -> appendField(declared, "tools " + v));
        if (declared.length() > 0) {
            card.append(" <small>").append(escape(declared.toString())).append(" (declared)</small>");
        }
    }

    private static void appendField(StringBuilder declared, String field) {
        if (declared.length() > 0) {
            declared.append(", ");
        }
        declared.append(field);
    }

    /**
     * Pending divided by the last hour's completions, the same estimate the reference page
     * {@code job_queue_status} shows. Nothing waiting is reported as no wait even when nothing
     * has completed either; otherwise an unknown rate is reported as unknown rather than as zero.
     */
    static String estimatedWait(int pendingCount, long completedLastHour) {
        if (pendingCount == 0) {
            return "0s";
        }
        if (completedLastHour == 0) {
            return "&mdash;";
        }
        long seconds = Math.round(3600.0 * pendingCount / completedLastHour);
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private static String percent(double fraction) {
        return round(100.0 * Math.min(1.0, fraction));
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
