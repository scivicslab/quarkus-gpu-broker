package com.scivicslab.gpubroker.rest;

import java.util.List;

import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * Builds the {@code GET /status} HTML: one stacked bar per queue (active /
 * idle / pending), auto-refreshing every 10 seconds. No template engine —
 * this is the service's only HTML page.
 */
final class StatusPageRenderer {

    private StatusPageRenderer() {
    }

    static String render(List<QueueStatus> statuses) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta http-equiv=\"refresh\" content=\"10\">")
                .append("<title>gpu-broker status</title>")
                .append("<style>")
                .append("body{font-family:sans-serif;margin:2em}")
                .append(".bar{display:flex;height:1.5em;width:100%;max-width:40em;border:1px solid #ccc}")
                .append(".active{background:#d9534f}.idle{background:#5cb85c}.pending{background:#428bca}")
                .append(".row{margin-bottom:1em}")
                .append("</style></head><body>")
                .append("<h1>gpu-broker status</h1>");

        if (statuses.isEmpty()) {
            html.append("<p>No queues discovered yet.</p>");
        }
        for (QueueStatus status : statuses) {
            html.append(renderRow(status));
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String renderRow(QueueStatus status) {
        QueueSnapshot s = status.snapshot();
        int total = s.activeCount() + s.idleCount() + s.pendingCount();

        StringBuilder row = new StringBuilder();
        row.append("<div class=\"row\"><strong>").append(escape(status.queueName())).append("</strong>")
                .append(" — active: ").append(s.activeCount())
                .append(", idle: ").append(s.idleCount())
                .append(", pending: ").append(s.pendingCount())
                .append("<div class=\"bar\">");
        if (total > 0) {
            appendSegment(row, "active", s.activeCount(), total);
            appendSegment(row, "idle", s.idleCount(), total);
            appendSegment(row, "pending", s.pendingCount(), total);
        }
        row.append("</div></div>");
        return row.toString();
    }

    private static void appendSegment(StringBuilder row, String cssClass, int count, int total) {
        if (count == 0) {
            return;
        }
        double widthPercent = 100.0 * count / total;
        row.append("<div class=\"").append(cssClass).append("\" style=\"width:")
                .append(widthPercent).append("%\"></div>");
    }

    private static String escape(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
