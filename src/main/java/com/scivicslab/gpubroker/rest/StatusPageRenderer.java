package com.scivicslab.gpubroker.rest;

import java.util.List;
import java.util.Map;

import com.scivicslab.gpubroker.config.BrokerConfig;
import com.scivicslab.gpubroker.history.EndpointBucket;
import com.scivicslab.gpubroker.history.GenerationTotals;
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
        return render(statuses, capabilities, historyByQueue, java.time.Instant.now());
    }

    /**
     * @param now which ten-minute window is the one still being filled, and so which is the newest
     *            one a rate may be read from ({@code GenerationRateWindowsAndLayout_260923_oo01})
     */
    static String render(List<QueueStatus> statuses, Map<String, BrokerConfig.EndpointCapability> capabilities,
                         Map<String, QueueHistorySnapshot> historyByQueue, java.time.Instant now) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                // One minute, because that is how often the finest figure on the page can change.
                // Reloading every ten seconds redrew the same numbers six times and made every
                // figure look current (LivenessFromWorkNotOnlyProbes_260920_oo01).
                .append("<meta http-equiv=\"refresh\" content=\"60\">")
                .append("<title>gpu-broker status</title>")
                .append("<link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\">")
                .append(style())
                .append("</head><body><header><h1>gpu-broker</h1>")
                .append("<p class=\"sub\">each row of figures names the window it covers, and every ")
                .append("figure says what it is divided by when you hover it. The page reloads once ")
                .append("a minute; the charts and bands below each card cover 24 h as 144 windows ")
                .append("of 10 min.</p>")
                .append("</header><main>");

        if (statuses.isEmpty()) {
            html.append("<p class=\"empty\">No queues discovered yet.</p>");
        }
        for (QueueStatus status : statuses) {
            html.append(renderCard(status, capabilities, historyByQueue.get(status.queueName()), now));
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
                + ".metrics{display:flex;gap:1.1rem;flex-wrap:wrap;font-variant-numeric:tabular-nums;"
                + "font-size:0.85rem;color:var(--muted)}"
                + ".metrics b{font-weight:600;color:var(--ink)}"
                + ".metrics small{font-size:0.85em;opacity:0.8}"
                + ".metrics span[title]{cursor:help}"
                + ".figurerows{display:flex;flex-direction:column;gap:0.3rem;margin-top:0.55rem}"
                + ".figurerow{display:flex;align-items:baseline;gap:0.9rem;flex-wrap:wrap}"
                + ".window{flex:0 0 7.5rem;font-size:0.72rem;letter-spacing:0.05em;"
                + "text-transform:uppercase;color:var(--muted)}"
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
                                     QueueHistorySnapshot snapshot, java.time.Instant now) {
        QueueSnapshot s = status.snapshot();
        List<QueueBucket> buckets = snapshot.queueBuckets();
        long completedLastHour = snapshot.completedLastHour();

        StringBuilder card = new StringBuilder();
        card.append("<div class=\"card\"><div class=\"head\"><span class=\"name\">")
                .append(escape(status.queueName())).append("</span></div>")
                .append("<div class=\"figurerows\">")
                .append(scaleRow("now", nowFigures(s, completedLastHour)))
                .append(scaleRow("last 10 min", closedWindowFigures(buckets, now)))
                .append(scaleRow("24 h peak — node", nodePeakFigures(buckets, now)))
                .append(scaleRow("24 h peak — one caller", callerPeakFigures(buckets, now)))
                .append("</div>");

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
    /** One row of figures under the queue's name, labelled with the window it covers. */
    private static String scaleRow(String window, String figures) {
        if (figures.isEmpty()) {
            return "";
        }
        return "<div class=\"figurerow\"><span class=\"window\">" + window + "</span>"
                + "<span class=\"metrics\">" + figures + "</span></div>";
    }

    /** What is true at this instant: the slots, the queue in front of them, and how long it is. */
    private static String nowFigures(QueueSnapshot s, long completedLastHour) {
        return metric("active", "--active", s.activeCount(), "slots",
                       "Workers generating a reply right now.")
                + metric("pending", "--pending", s.pendingCount(), "jobs",
                       "Jobs accepted but not yet handed to a worker.")
                + metric("idle", "--idle", s.idleCount(), "slots",
                       "Workers attached to this queue with nothing to do.")
                + figure("wait", estimatedWait(s.pendingCount(), completedLastHour), "",
                       "Estimated time before a job submitted now starts: "
                       + "pending jobs divided by the jobs completed in the last hour.")
                // Next to wait because it is wait's divisor, and on this row rather than the
                // ten-minute one because it must not disappear with the rates: the unit carries
                // its own window (GenerationRateWindowsAndLayout_260923_oo01).
                + figure("done", Long.toString(completedLastHour), "jobs/h",
                       "Jobs completed in the last hour — a one-hour window, which is why the "
                       + "unit spells it out. This is the divisor behind wait.");
    }

    /**
     * The rates of the newest ten-minute window that has closed.
     *
     * <p>Nothing at all until one has closed — a window still being filled has no honest divisor
     * ({@code GenerationRateWindowsAndLayout_260923_oo01}). Once one has, an em dash rather than
     * {@code 0.0} whenever no reply ended in it: no reply to measure and a measured speed of zero
     * are different states, and the page used to show the same {@code 0.0} for both.
     */
    private static String closedWindowFigures(List<QueueBucket> buckets, java.time.Instant now) {
        QueueBucket closed = GenerationWindows.lastClosed(buckets, now);
        if (closed == null) {
            return "";
        }
        GenerationTotals totals = closed.generated();
        boolean measured = totals.generations() > 0;
        String queued = !measured ? DASH
                : totals.meanQueuedMs() / 1000.0 >= 0.1
                        ? oneDecimal(totals.meanQueuedMs() / 1000.0) + "s" : "0s";
        return figure("tok/s", measured
                        ? rate(totals.tokensPerSecondOver(GenerationWindows.length())) : DASH,
                       "total", "Tokens generated in that window divided by its 600 seconds. "
                       + "Rises as the queue takes on more replies at once, until it stops rising "
                       + "— that is where the capacity is.")
                + figure("", measured ? rate(totals.tokensPerSecondPerReply()) : DASH,
                       "per reply", "Tokens per second of a reply's own generation time, averaged "
                       + "over the replies that ended in that window. This is what one caller "
                       + "waiting sees, and it falls as the queue takes on more at once.")
                + figure("", measured ? rate(totals.charactersPerSecondPerReply()) : DASH,
                       "chars/s per reply", "The same per-reply speed counted in characters. "
                       + "Tokenizers differ between models, characters do not, so this is the "
                       + "figure to compare two models with.")
                + figure("queued", queued, "",
                       "Mean time a reply spent waiting for a worker before generation began.")
                // Unlike the rates, zero here is a real reading: a window can pass with nothing
                // generating. Only an unsampled window has no occupancy to report.
                + figure("at", closed.sampleCount() == 0 ? DASH : oneDecimal(closed.activeAverage()),
                       "slots busy",
                       "Slots generating on average through that window. The per-reply figures to "
                       + "the left were measured at this occupancy, and fall as it rises.");
    }

    /**
     * What the deployment as a whole reached, over the closed windows still retained.
     *
     * <p>Kept apart from what one reply reached, because they are the two things a reader comes to
     * this page for and they move in opposite directions: filling every slot is how the machine
     * reaches its throughput and is also what makes one caller's reply slow.
     */
    private static String nodePeakFigures(List<QueueBucket> buckets, java.time.Instant now) {
        GenerationWindows.Peaks peaks = GenerationWindows.peaks(buckets, now);
        if (peaks.noNodeFigure()) {
            return "";
        }
        return figure("tok/s", rate(peaks.tokensPerSecondBestMinuteAtFullSlots()), "all slots busy",
                       "Everything the queue produced in its busiest minute among the minutes the "
                       + "observation found every slot generating. This is the deployment's "
                       + "throughput as a machine: what the hardware is worth when it is fed.")
                + figure("", rate(peaks.tokensPerSecondBestMinute()), "busiest 1 min",
                       "The busiest minute whatever the occupancy. Equal to the figure on its "
                       + "left when the peak happened to fall in a full minute, and lower when "
                       + "the deployment was never filled.")
                + figure("", rate(peaks.tokensPerSecondBestWindow()), "best 10 min",
                       "The highest any single closed ten-minute window reached. Lower than the "
                       + "best minute whenever the load came in bursts — this is what the queue "
                       + "sustains, that is what it peaks at.");
    }

    /**
     * What one reply achieved, at the two occupancies worth telling apart: a caller with the
     * deployment to themselves, and a caller sharing it with every other slot.
     */
    private static String callerPeakFigures(List<QueueBucket> buckets, java.time.Instant now) {
        GenerationWindows.Peaks peaks = GenerationWindows.peaks(buckets, now);
        if (peaks.noCallerFigure()) {
            return "";
        }
        return figure("tok/s", rate(peaks.tokensPerSecondOneReplyAlone()), "alone",
                       "The fastest reply that had the whole queue to itself — nothing else "
                       + "generating when it started or when it finished. What one person waiting "
                       + "feels on an idle deployment, and the upper bound on that feeling.")
                + figure("", rate(peaks.tokensPerSecondOneReplyAtFullSlots()), "all slots busy",
                       "The fastest reply that ran with every slot generating at both ends of it. "
                       + "What one person feels when the deployment is full. The gap from the "
                       + "figure on its left is what sharing costs them.");
    }

    /** An em dash: the figure had nothing to measure, which is not the same as measuring zero. */
    private static final String DASH = "&mdash;";

    /** One labelled figure carrying its own definition, for the reader who hovers it. */
    private static String figure(String label, String value, String unit, String title) {
        return "<span title=\"" + escape(title) + "\">"
                + (label.isEmpty() ? "" : label + " ")
                + "<b>" + value + "</b>"
                + (unit.isEmpty() ? "" : " <small>" + unit + "</small>")
                + "</span>";
    }

    private static String oneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * A rate, with the two ways of being small kept apart: a queue that generated a handful of
     * tokens in ten minutes is not a queue that generated none, and rounding both to {@code 0.0}
     * was how the page said they were the same
     * ({@code GenerationRateWindowsAndLayout_260923_oo01}).
     */
    private static String rate(double value) {
        if (value <= 0) {
            return DASH;
        }
        return value < 0.05 ? "&lt;0.1" : oneDecimal(value);
    }

    private static String metric(String label, String colorVar, int count, String unit, String title) {
        return "<span title=\"" + escape(title) + "\">"
                + "<i class=\"swatch\" style=\"background:var(" + colorVar + ")\"></i>"
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
