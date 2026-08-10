package com.scivicslab.gpubroker.rest;

import java.util.List;

import com.scivicslab.gpubroker.model.QueueSnapshot;
import com.scivicslab.gpubroker.model.QueueStatus;

/**
 * Builds the status page HTML: one stacked bar per queue (active / idle /
 * pending) plus the actual endpoint addresses behind it, auto-refreshing
 * every 10 seconds. No template engine — this is the service's only HTML
 * page.
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
                .append(".bar .active{background:#d9534f}.bar .idle{background:#5cb85c}.bar .pending{background:#428bca}")
                .append(".row{margin-bottom:1em}")
                .append(".endpoints{margin-top:0.3em;font-size:0.9em;color:#555}")
                .append(".endpoints .active{color:#d9534f}.endpoints .idle{color:#5cb85c}")
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
        row.append("</div>");
        appendEndpointList(row, s);
        row.append("</div>");
        return row.toString();
    }

    private static void appendEndpointList(StringBuilder row, QueueSnapshot s) {
        if (s.activeEndpointIds().isEmpty() && s.idleEndpointIds().isEmpty()) {
            return;
        }
        row.append("<div class=\"endpoints\">endpoints: ");
        boolean first = true;
        for (String endpointId : s.activeEndpointIds()) {
            first = appendEndpoint(row, first, "active", endpointId);
        }
        for (String endpointId : s.idleEndpointIds()) {
            first = appendEndpoint(row, first, "idle", endpointId);
        }
        row.append("</div>");
    }

    private static boolean appendEndpoint(StringBuilder row, boolean first, String cssClass, String endpointId) {
        if (!first) {
            row.append(", ");
        }
        row.append("<span class=\"").append(cssClass).append("\">")
                .append(escape(endpointId)).append(" (").append(cssClass).append(")</span>");
        return false;
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
