package com.scivicslab.gpubroker.rest;

import java.util.List;
import java.util.Map;

import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.history.EndpointBucket;
import com.scivicslab.gpubroker.history.QueueBucket;
import com.scivicslab.gpubroker.history.StatusHistoryStore;
import com.scivicslab.gpubroker.history.StatusHistoryStore.QueueHistorySnapshot;
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
                         Map<String, QueueHistorySnapshot> historyByQueue) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"refresh\" content=\"10\">")
                .append("<title>gpu-broker status</title>")
                .append(style())
                .append("</head><body><header><h1>gpu-broker</h1>")
                .append("<p class=\"sub\">the numbers below reload every 10s &middot; ")
                .append("liveness is probed every minute &middot; the charts gain one step every 10 min, ")
                .append("covering 24 hours</p>")
                .append("</header><main>");

        if (statuses.isEmpty()) {
            html.append("<p class=\"empty\">No queues discovered yet.</p>");
        }
        for (QueueStatus status : statuses) {
            html.append(renderCard(status, capabilities, historyByQueue.get(status.queueName())));
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
                + ".metrics small{font-size:0.85em;opacity:0.8}"
                + ".swatch{display:inline-block;width:0.6em;height:0.6em;border-radius:2px;margin-right:0.35em}"
                + ".now{display:flex;height:0.65rem;margin:0.7rem 0 0.2rem;background:var(--bg);"
                + "border-radius:3px;overflow:hidden}"
                + ".now i{display:block}"
                + ".scale{display:flex;justify-content:space-between;color:var(--muted);font-size:0.72rem}"
                + "section{margin-top:1.1rem}"
                + "h2{margin:0 0 0.4rem;font-size:0.78rem;font-weight:600;text-transform:uppercase;"
                + "letter-spacing:0.06em;color:var(--muted)}"
                + "svg{display:block;width:100%;height:auto}"
                + ".chart{display:flex;gap:0.45rem;align-items:stretch}"
                + ".yaxis{width:3.4rem;flex:none;display:flex;flex-direction:column;justify-content:space-between;text-align:right;font-size:0.7rem;color:var(--muted);font-variant-numeric:tabular-nums}"
                + ".plot{flex:1;min-width:0}"
                + ".plot svg{width:100%;height:100%;display:block}"
                + ".unit{font-size:0.7rem;color:var(--muted);margin-bottom:0.15rem}"
                + ".bands{display:flex;flex-direction:column;gap:0.3rem}"
                + ".band{display:flex;align-items:center;gap:0.7rem}"
                + ".addr{width:16rem;flex:none;font-variant-numeric:tabular-nums;font-size:0.82rem;"
                + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + ".addr small{color:var(--muted)}"
                + ".band .strip{flex:1}"
                + "</style>";
    }

    private static String renderCard(QueueStatus status, Map<String, BrokerConfig.EndpointCapability> capabilities,
                                     QueueHistorySnapshot snapshot) {
        QueueSnapshot s = status.snapshot();
        List<QueueBucket> buckets = snapshot.queueBuckets();
        long completedLastHour = snapshot.completedLastHour();

        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card\"><div class=\"head\"><span class=\"name\">")
                .append(escape(status.queueName())).append("</span><span class=\"metrics\">")
                .append(metric("active", "--active", s.activeCount(), "slots"))
                .append(metric("pending", "--pending", s.pendingCount(), "jobs"))
                .append(metric("idle", "--idle", s.idleCount(), "slots"))
                .append("<span>wait <b>").append(estimatedWait(s.pendingCount(), completedLastHour)).append("</b></span>")
                .append("<span>done <b>").append(completedLastHour).append("</b> <small>jobs/h</small></span>")
                .append("</span></div>");

        appendCurrentBar(card, s);
        appendQueueAndThroughputChart(card, buckets);
        appendUtilizationChart(card, buckets, s);
        appendLivenessBands(card, snapshot, capabilities);

        card.append("</div>");
        return card.toString();
    }

    /**
     * One labelled number with its unit spelled out. {@code active} and {@code idle} count
     * {@code AiServiceEndpointWorker}s (slots), {@code pending} counts jobs still in {@code
     * JobQueue}'s deque — without the unit on the page, a reader has to already know which of
     * the three is which.
     */
    private static String metric(String label, String colorVar, int count, String unit) {
        return "<span><i class=\"swatch\" style=\"background:var(" + colorVar + ")\"></i>"
                + label + " <b>" + count + "</b> <small>" + unit + "</small></span>";
    }

    /**
     * The current reading as a bar whose full width is the queue's slot count, so a full bar
     * means every slot is busy. Pending is drawn beyond the slot count when the backlog exceeds
     * capacity — that overflow is exactly what a reader wants to see.
     */
    private static void appendCurrentBar(StringBuilder card, QueueSnapshot s) {
        int slots = Math.max(1, s.activeCount() + s.idleCount());
        double full = Math.max(slots, s.activeCount() + s.idleCount() + s.pendingCount());
        card.append("<div class=\"now\">");
        appendSegment(card, "--active", s.activeCount(), full);
        appendSegment(card, "--pending", s.pendingCount(), full);
        appendSegment(card, "--idle", s.idleCount(), full);
        card.append("</div><div class=\"scale\"><span>0</span><span>")
                .append(slots).append(" slots</span></div>");
    }

    private static void appendSegment(StringBuilder card, String colorVar, int count, double full) {
        if (count == 0) {
            return;
        }
        card.append("<i style=\"width:").append(percent(count / full))
                .append("%;background:var(").append(colorVar).append(")\"></i>");
    }

    /**
     * Queue length and throughput share one axis because both count jobs. That makes their
     * heights comparable: a backlog standing above the throughput line by a factor of three is
     * three ten-minute periods of work — the same quantity the header's "wait" reports.
     */
    private static void appendQueueAndThroughputChart(StringBuilder card, List<QueueBucket> buckets) {
        card.append("<section><h2>waiting and done &mdash; 24h, 10 min per step</h2>");
        if (buckets.isEmpty()) {
            card.append("<p class=\"empty\">No history recorded yet.</p></section>");
            return;
        }
        double peak = 0;
        for (QueueBucket bucket : buckets) {
            peak = Math.max(peak, Math.max(bucket.pendingAverage(), bucket.completed()));
        }
        double ceiling = niceCeiling(peak);

        card.append("<div class=\"unit\">jobs &mdash; area: waiting, line: done per 10 min</div>")
                .append(axisOpen(ceiling, ""));
        appendGridLines(card);
        int offset = StatusHistoryStore.BUCKETS_PER_DAY - buckets.size();
        for (int i = 0; i < buckets.size(); i++) {
            double height = CONGESTION_HEIGHT * buckets.get(i).pendingAverage() / ceiling;
            appendColumn(card, (offset + i) * 5, CONGESTION_HEIGHT - height, height, "--pending");
        }
        appendLine(card, buckets, offset, ceiling, bucket -> (double) bucket.completed());
        card.append(axisClose());
        appendTimeScale(card);
        card.append("</section>");
    }

    /**
     * Busy slots as a percentage of the slots attached to the queue. A percentage has a ceiling
     * that does not move, so a full panel means the same thing on a 1-slot queue and a 64-slot
     * one, and the two can be compared without reading the numbers.
     */
    private static void appendUtilizationChart(StringBuilder card, List<QueueBucket> buckets, QueueSnapshot now) {
        card.append("<section><h2>slot utilization &mdash; 24h, 10 min per step</h2>");
        if (buckets.isEmpty()) {
            card.append("<p class=\"empty\">No history recorded yet.</p></section>");
            return;
        }
        card.append("<div class=\"unit\">% of ").append(now.activeCount() + now.idleCount()).append(" slots</div>")
                .append(axisOpen(100, "%"));
        appendGridLines(card);
        int offset = StatusHistoryStore.BUCKETS_PER_DAY - buckets.size();
        for (int i = 0; i < buckets.size(); i++) {
            double height = CONGESTION_HEIGHT * buckets.get(i).utilizationPercent() / 100.0;
            appendColumn(card, (offset + i) * 5, CONGESTION_HEIGHT - height, height, "--active");
        }
        card.append(axisClose());
        appendTimeScale(card);
        card.append("</section>");
    }

    /** The y-axis labels sit in HTML beside the SVG, so stretching the plot never distorts them. */
    private static String axisOpen(double ceiling, String unit) {
        return "<div class=\"chart\"><div class=\"yaxis\"><span>" + trim(ceiling) + unit
                + "</span><span>" + trim(ceiling / 2) + unit + "</span><span>0" + unit + "</span></div>"
                + "<div class=\"plot\" style=\"height:" + CONGESTION_HEIGHT + "px\">"
                + "<svg viewBox=\"0 0 " + CHART_WIDTH + " " + CONGESTION_HEIGHT + "\" preserveAspectRatio=\"none\" role=\"img\">";
    }

    private static String axisClose() {
        return "</svg></div></div>";
    }

    private static void appendGridLines(StringBuilder card) {
        for (int fraction : new int[] {0, 1, 2}) {
            double y = CONGESTION_HEIGHT * fraction / 2.0;
            card.append("<line x1=\"0\" y1=\"").append(round(y)).append("\" x2=\"").append(CHART_WIDTH)
                    .append("\" y2=\"").append(round(y))
                    .append("\" stroke=\"var(--line)\" stroke-width=\"1\" vector-effect=\"non-scaling-stroke\"/>");
        }
    }

    private static void appendTimeScale(StringBuilder card) {
        card.append("<div class=\"scale\"><span>24h ago</span><span>12h ago</span><span>now</span></div>");
    }

    private static void appendColumn(StringBuilder card, int x, double y, double height, String colorVar) {
        if (height <= 0) {
            return;
        }
        card.append("<rect x=\"").append(x).append("\" y=\"").append(round(y))
                .append("\" width=\"5\" height=\"").append(round(height))
                .append("\" fill=\"var(").append(colorVar).append(")\"/>");
    }

    private static void appendLine(StringBuilder card, List<QueueBucket> buckets, int offset, double ceiling,
                                    java.util.function.ToDoubleFunction<QueueBucket> value) {
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < buckets.size(); i++) {
            double y = CONGESTION_HEIGHT - CONGESTION_HEIGHT * value.applyAsDouble(buckets.get(i)) / ceiling;
            points.append((offset + i) * 5 + 2).append(",").append(round(y)).append(" ");
        }
        card.append("<polyline points=\"").append(points.toString().strip())
                .append("\" fill=\"none\" stroke=\"var(--ink)\" stroke-width=\"1.2\"")
                .append(" stroke-linejoin=\"round\" vector-effect=\"non-scaling-stroke\"/>");
    }

    /** The smallest of 1, 2, 5, 10, 20, 50, ... that is at least {@code value} — so the axis reads in round numbers. */
    static double niceCeiling(double value) {
        if (value <= 1) {
            return 1;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        for (double step : new double[] {1, 2, 5}) {
            if (step * magnitude >= value) {
                return step * magnitude;
            }
        }
        return 10 * magnitude;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** One row per address: 144 cells coloured by how many of that bucket's probes it answered. */
    private static void appendLivenessBands(StringBuilder card, QueueHistorySnapshot snapshot,
                                            Map<String, BrokerConfig.EndpointCapability> capabilities) {
        card.append("<section><h2>liveness &mdash; 24h, probed every minute</h2>");
        if (snapshot.endpointHistories().isEmpty()) {
            card.append("<p class=\"empty\">No probe result recorded yet.</p></section>");
            return;
        }
        card.append("<div class=\"bands\">");
        for (var entry : snapshot.endpointHistories().entrySet()) {
            card.append("<div class=\"band\"><span class=\"addr\">").append(escape(entry.getKey()));
            appendDeclaredCapability(card, capabilities.get(entry.getKey()));
            card.append("</span><span class=\"strip\">");
            appendBand(card, entry.getValue());
            card.append("</span></div>");
        }
        card.append("</div></section>");
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
