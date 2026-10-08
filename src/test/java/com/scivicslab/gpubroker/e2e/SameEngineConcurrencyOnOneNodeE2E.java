package com.scivicslab.gpubroker.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Whether one OCR service on one GPU node survives two requests arriving at the same time, and
 * whether it gains anything by it.
 *
 * <p>This is a different question from {@link ConcurrentOcrOnOneNodeE2E}, which measured Marker and
 * YomiToku overlapping — two services, two queues, one node. Here there is one service and one
 * queue, and the requests that overlap are requests for the same service. The broker keeps these
 * apart: it counts slots per (queue, endpoint), so
 * {@code MarkerOcrProbe.defaultMaxConcurrency()} and {@code YomiTokuOcrProbe.defaultMaxConcurrency()}
 * — both 1 — govern this case and say nothing about the other one.</p>
 *
 * <p>Those two 1s rest on different grounds. Marker's carries the note "measured intermittent 500
 * under 2 concurrent requests — a reliability constraint, not tuning", and a separate observation
 * recorded elsewhere says Marker answers 500 when two requests carry the <em>same</em> file name.
 * YomiToku's says only "no measured benefit on the currently deployed GPU". Neither claim was
 * re-measured after both services moved to the DGX Spark machines {@code 192.168.5.16} and
 * {@code 192.168.5.17}.</p>
 *
 * <p>Three things are therefore varied, and the service is called directly rather than through the
 * broker so that this program decides exactly what overlaps:</p>
 * <ol>
 *   <li><b>How many requests at once</b> — 1, then 2 (more with {@code -De2e.ocr.levels}).</li>
 *   <li><b>How alike the overlapping requests are</b> — identical file name and identical bytes;
 *       different file names, identical bytes; different file names and different bytes. If the 500
 *       comes from two requests sharing a name on disk rather than from the GPU, only the first of
 *       the three fails, and the limit of 1 is the wrong fix for it.</li>
 *   <li><b>Which service</b> — Marker on port 8001 with {@code force_ocr=true} so it rasterises and
 *       runs its models, YomiToku on port 8013, which always rasterises.</li>
 * </ol>
 *
 * <p>What comes out: the failure count per cell, so whether two at once breaks the service and
 * whether that depends on the requests being alike; and the wall time of a group against the time of
 * one call alone, so whether the service overlaps the work internally (group wall time stays near one
 * call) or runs them one after another anyway (group wall time approaches the sum). The second
 * number decides whether raising the limit above 1 could buy any throughput even if it is safe.</p>
 *
 * <p>Plain Java with {@code main()}, no JUnit, per {@code TestingStandard_260404_oo01} section 3.
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.gpubroker.e2e.SameEngineConcurrencyOnOneNodeE2E \
 *     -Dexec.classpathScope=test -De2e.ocr.node=192.168.5.16 -De2e.ocr.rounds=5
 * </pre>
 * Options: {@code -De2e.ocr.levels=1,2,4} how many requests at once, {@code -De2e.ocr.engines=marker}
 * to measure one service, {@code -De2e.ocr.rounds} groups per cell.
 */
class SameEngineConcurrencyOnOneNodeE2E extends GpuBrokerE2EBase {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String NODE = System.getProperty("e2e.ocr.node", "192.168.5.16");
    private static final int ROUNDS = Integer.getInteger("e2e.ocr.rounds", 5);
    private static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("e2e.ocr.timeout", 600));
    private static final List<Integer> LEVELS = Arrays.stream(
            System.getProperty("e2e.ocr.levels", "1,2").split(",")).map(String::trim).map(Integer::parseInt).toList();
    private static final List<String> ENGINES = Arrays.stream(
            System.getProperty("e2e.ocr.engines", "marker,yomitoku").split(",")).map(String::trim).toList();

    /** One service, as it is actually called. */
    record Engine(String name, int port, String path, Map<String, String> fields) {
    }

    private static final Engine MARKER = new Engine("marker", 8001, "/marker/upload",
            Map.of("page_range", "0", "output_format", "markdown", "force_ocr", "true"));
    private static final Engine YOMITOKU = new Engine("yomitoku", 8013, "/ocr/markdown",
            Map.of("page", "0"));

    /** How alike the requests that overlap are. */
    enum Payload {
        /** Same file name, same bytes: the case a 500 on a shared temporary path would hit. */
        IDENTICAL("same name, same bytes"),
        /** Different file names, same bytes: separates the name from the work. */
        DISTINCT_NAME("different names, same bytes"),
        /** Different file names and different bytes: two unrelated pages. */
        DISTINCT_PAGE("different names, different bytes");

        final String label;

        Payload(String label) {
            this.label = label;
        }
    }

    /** What one call did. */
    record Call(boolean ok, double seconds, String detail) {
    }

    /** One measured cell: a service, a number of requests at once, and how alike they were. */
    record Cell(String engine, int level, Payload payload, List<Call> calls, List<Double> wall) {
    }

    private final List<Cell> cells = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        new SameEngineConcurrencyOnOneNodeE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- SameEngineConcurrencyOnOneNodeE2E --- node " + NODE
                + ", levels " + LEVELS + ", " + ROUNDS + " groups per cell");
        System.out.println("each call goes straight to the service, not through the broker\n");

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            for (String engineName : ENGINES) {
                Engine engine = engineName.equals("marker") ? MARKER : YOMITOKU;
                for (int level : LEVELS) {
                    // With one request at a time there is nothing for it to be alike to, so the
                    // three payload cells would measure the same thing; run one of them.
                    List<Payload> payloads = level == 1 ? List.of(Payload.IDENTICAL) : List.of(Payload.values());
                    for (Payload payload : payloads) {
                        cells.add(measure(pool, engine, level, payload));
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        report();
    }

    /** Sends {@code level} requests at once, {@code ROUNDS} times, and records every call. */
    private Cell measure(ExecutorService pool, Engine engine, int level, Payload payload) {
        List<Call> calls = new ArrayList<>();
        List<Double> wall = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            List<CompletableFuture<Call>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < level; i++) {
                final int index = i;
                final int r = round;
                futures.add(CompletableFuture.supplyAsync(() -> call(engine, payload, r, index), pool));
            }
            List<Call> group = futures.stream().map(CompletableFuture::join).toList();
            double groupWall = elapsed(t0);
            calls.addAll(group);
            wall.add(groupWall);
            StringBuilder marks = new StringBuilder();
            for (Call c : group) {
                marks.append(String.format("%s %5.1fs  ", c.ok() ? "ok  " : "FAIL", c.seconds()));
            }
            System.out.printf("%-9s %d at once  %-28s group %d  %s wall %5.1fs%n",
                    engine.name(), level, payload.label, round, marks, groupWall);
        }
        for (Call c : calls) {
            if (!c.ok()) {
                System.out.printf("    %s, %d at once, %s: %s%n", engine.name(), level, payload.label, c.detail());
            }
        }
        return new Cell(engine.name(), level, payload, calls, wall);
    }

    /**
     * One request. {@code payload} decides the file name and the bytes: under
     * {@link Payload#IDENTICAL} every request in every group carries the same name and the same
     * page, under {@link Payload#DISTINCT_NAME} the name alone changes, and under
     * {@link Payload#DISTINCT_PAGE} the page carries the round and index in its text as well.
     */
    private Call call(Engine engine, Payload payload, int round, int index) {
        String fileName = switch (payload) {
            case IDENTICAL -> "page.pdf";
            default -> "page-" + round + "-" + index + ".pdf";
        };
        String pageLabel = payload == Payload.DISTINCT_PAGE ? "group " + round + " request " + index : "";
        byte[] pdf = ConcurrentOcrOnOneNodeE2E.densePagePdf(pageLabel);

        long t0 = System.nanoTime();
        try {
            String boundary = "----sameEngine" + System.nanoTime();
            byte[] body = FleetHealthE2E.multipart(boundary, engine.fields(),
                    "file", fileName, "application/pdf", pdf);
            HttpResponse<String> r = post("http://" + NODE + ":" + engine.port() + engine.path(),
                    "multipart/form-data; boundary=" + boundary, body);
            if (r.statusCode() != 200) {
                return new Call(false, elapsed(t0), "HTTP " + r.statusCode() + " for " + fileName + ": " + head(r.body()));
            }
            boolean ok = engine == MARKER
                    ? r.body().contains("\"success\": true") || r.body().contains("\"success\":true")
                    : r.body().contains("markdown");
            return new Call(ok, elapsed(t0), ok ? "read" : "HTTP 200 but " + head(r.body()));
        } catch (Exception e) {
            return new Call(false, elapsed(t0), e.getClass().getSimpleName() + " for " + fileName + ": " + e.getMessage());
        }
    }

    // --- reporting ---------------------------------------------------------------------------

    private void report() {
        System.out.println();
        System.out.printf("%-10s %-9s %-28s %-7s %-8s %-12s %-12s%n",
                "service", "at once", "requests", "calls", "failed", "median call", "median group");
        for (Cell c : cells) {
            System.out.printf("%-10s %-9d %-28s %-7d %-8d %-12.1f %-12.1f%n",
                    c.engine(), c.level(), c.level() == 1 ? "one at a time" : c.payload().label,
                    c.calls().size(), failed(c), median(c.calls().stream().map(Call::seconds).toList()),
                    median(c.wall()));
        }
        System.out.println();

        long total = 0;
        for (String engineName : ENGINES) {
            total += readEngine(engineName);
        }

        System.out.println();
        if (total == 0) {
            System.out.println("SameEngineConcurrencyOnOneNodeE2E: PASSED on " + NODE
                    + " -- no call failed at any level, with any degree of likeness");
            return;
        }
        System.out.println("SameEngineConcurrencyOnOneNodeE2E: " + total + " call(s) failed on " + NODE
                + " -- see the lines above for the status and body of each");
        System.exit(1);
    }

    /** Prints what the cells of one service say, and returns how many of its calls failed. */
    private long readEngine(String engineName) {
        List<Cell> mine = cells.stream().filter(c -> c.engine().equals(engineName)).toList();
        if (mine.isEmpty()) {
            return 0;
        }
        Cell base = mine.stream().filter(c -> c.level() == 1).findFirst().orElse(null);
        long failures = mine.stream().mapToLong(SameEngineConcurrencyOnOneNodeE2E::failed).sum();

        System.out.println(engineName + " on " + NODE + ":");
        for (Cell c : mine) {
            if (c.level() == 1) {
                continue;
            }
            long f = failed(c);
            System.out.printf("  %d at once, %s: %s%n", c.level(), c.payload().label,
                    f == 0 ? "all " + c.calls().size() + " calls answered"
                            : f + " of " + c.calls().size() + " calls failed");
        }
        if (base != null) {
            double one = median(base.calls().stream().map(Call::seconds).toList());
            for (Cell c : mine) {
                if (c.level() == 1) {
                    continue;
                }
                double group = median(c.wall());
                String reading = group <= one * 1.25
                        ? "the service runs them at the same time"
                        : group >= one * c.level() * 0.85
                                ? "the service runs them one after another"
                                : "the service overlaps part of the work";
                System.out.printf("  %d at once, %s: one call alone %.1fs, the group %.1fs -- %s%n",
                        c.level(), c.payload().label, one, group, reading);
            }
        }
        return failures;
    }

    private static long failed(Cell c) {
        return c.calls().stream().filter(call -> !call.ok()).count();
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static HttpResponse<String> post(String url, String contentType, byte[] body)
            throws java.io.IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static double elapsed(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    private static String head(String s) {
        String flat = s == null ? "" : s.replace('\n', ' ');
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }
}
