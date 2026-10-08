package com.scivicslab.gpubroker.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Whether the two OCR services on one GPU node can read a page at the same time, measured rather
 * than assumed.
 *
 * <p>html-saurus imports a PDF by sending every page to both: YomiToku reads the prose, Marker reads
 * the mathematics, and the two outputs are merged. The broker counts its slots per queue, so one
 * node runs one Marker job and one YomiToku job at once -- and on 2026-10-06 half the pages of an
 * import failed on {@code 192.168.5.16} with {@code CUBLAS_STATUS_INTERNAL_ERROR} while the other
 * node, same model of machine, same images, read every page it was given. "They collide on the GPU"
 * was the guess. This measures it.</p>
 *
 * <p>Both services are called directly, not through the broker, so this program decides exactly what
 * overlaps. One round is:</p>
 * <ol>
 *   <li>Marker alone, timed.</li>
 *   <li>YomiToku alone, timed.</li>
 *   <li>Both started together, each timed, and the wall time of the pair.</li>
 * </ol>
 *
 * <p>Three things come out of it. Whether a request fails only when the two overlap. Whether running
 * them together is faster than running them one after the other -- if the pair's wall time is the
 * longer of the two, the GPU really does interleave them; if it is the sum, the driver serialises
 * them anyway and the overlap buys nothing. And how much each one slows down while the other runs.</p>
 *
 * <p>The page is built here rather than read from disk, so the measurement does not depend on a file
 * that happens to be on one machine. It carries 32 lines of text and is sent to Marker with {@code
 * force_ocr=true}, which makes Marker rasterise and read it with the same models a scanned page
 * would use instead of lifting the embedded text; YomiToku always rasterises.</p>
 *
 * <p>Plain Java with {@code main()}, no JUnit, per {@code TestingStandard_260404_oo01} section 3.
 * <pre>
 *   mvn test-compile exec:java \
 *     -Dexec.mainClass=com.scivicslab.gpubroker.e2e.ConcurrentOcrOnOneNodeE2E \
 *     -Dexec.classpathScope=test -De2e.ocr.node=192.168.5.16 -De2e.ocr.rounds=5
 * </pre>
 */
class ConcurrentOcrOnOneNodeE2E extends GpuBrokerE2EBase {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String NODE = System.getProperty("e2e.ocr.node", "192.168.5.16");
    private static final int ROUNDS = Integer.getInteger("e2e.ocr.rounds", 5);
    private static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("e2e.ocr.timeout", 600));

    /** What one call did: how long it took, and what came back. */
    record Call(String engine, boolean ok, double seconds, String detail) {
    }

    private final List<Call> alone = new ArrayList<>();
    private final List<Call> together = new ArrayList<>();
    private final List<Double> aloneWall = new ArrayList<>();
    private final List<Double> togetherWall = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        new ConcurrentOcrOnOneNodeE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- ConcurrentOcrOnOneNodeE2E --- node " + NODE + ", " + ROUNDS + " rounds");
        byte[] page = densePagePdf();
        System.out.printf("one-page PDF: %d bytes, 32 lines, Marker forced to OCR it%n%n", page.length);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                long t0 = System.nanoTime();
                Call m = marker(page);
                Call y = yomitoku(page);
                double wallAlone = elapsed(t0);
                alone.add(m);
                alone.add(y);
                aloneWall.add(wallAlone);
                System.out.printf("round %d  one after the other  marker %s %5.1fs  yomitoku %s %5.1fs  wall %5.1fs%n",
                        round, mark(m), m.seconds(), mark(y), y.seconds(), wallAlone);

                t0 = System.nanoTime();
                CompletableFuture<Call> mf = CompletableFuture.supplyAsync(() -> marker(page), pool);
                CompletableFuture<Call> yf = CompletableFuture.supplyAsync(() -> yomitoku(page), pool);
                Call mc = mf.join();
                Call yc = yf.join();
                double wallTogether = elapsed(t0);
                together.add(mc);
                together.add(yc);
                togetherWall.add(wallTogether);
                System.out.printf("round %d  at the same time     marker %s %5.1fs  yomitoku %s %5.1fs  wall %5.1fs%n",
                        round, mark(mc), mc.seconds(), mark(yc), yc.seconds(), wallTogether);
            }
        } finally {
            pool.shutdownNow();
        }
        report();
    }

    private static String mark(Call c) {
        return c.ok() ? "ok  " : "FAIL";
    }

    // --- the two engines ----------------------------------------------------------------------

    private Call marker(byte[] pdf) {
        long t0 = System.nanoTime();
        try {
            String boundary = "----ocrConc" + System.nanoTime();
            byte[] body = FleetHealthE2E.multipart(boundary,
                    Map.of("page_range", "0", "output_format", "markdown", "force_ocr", "true"),
                    "file", "page.pdf", "application/pdf", pdf);
            HttpResponse<String> r = post("http://" + NODE + ":8001/marker/upload",
                    "multipart/form-data; boundary=" + boundary, body);
            if (r.statusCode() != 200) {
                return new Call("marker", false, elapsed(t0), "HTTP " + r.statusCode() + ": " + head(r.body()));
            }
            boolean ok = r.body().contains("\"success\": true") || r.body().contains("\"success\":true");
            return new Call("marker", ok, elapsed(t0), ok ? "read" : head(r.body()));
        } catch (Exception e) {
            return new Call("marker", false, elapsed(t0), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Call yomitoku(byte[] pdf) {
        long t0 = System.nanoTime();
        try {
            String boundary = "----ocrConc" + System.nanoTime();
            byte[] body = FleetHealthE2E.multipart(boundary, Map.of("page", "0"),
                    "file", "page.pdf", "application/pdf", pdf);
            HttpResponse<String> r = post("http://" + NODE + ":8013/ocr/markdown",
                    "multipart/form-data; boundary=" + boundary, body);
            if (r.statusCode() != 200) {
                return new Call("yomitoku", false, elapsed(t0), "HTTP " + r.statusCode() + ": " + head(r.body()));
            }
            boolean ok = r.body().contains("markdown");
            return new Call("yomitoku", ok, elapsed(t0), ok ? "read" : head(r.body()));
        } catch (Exception e) {
            return new Call("yomitoku", false, elapsed(t0), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // --- the page ----------------------------------------------------------------------------

    /**
     * A page with 32 lines of ordinary prose, so the OCR models have a page's worth of work rather
     * than one short line. Built the same way as {@link FleetHealthE2E#onePagePdf}: catalog, pages,
     * one page, a font, a content stream, and a cross-reference table with real byte offsets.
     */
    static byte[] densePagePdf() {
        return densePagePdf("");
    }

    /**
     * The same page with {@code label} written above the lines, so that two requests can carry pages
     * that differ in their bytes and not only in their file names. An empty label writes nothing.
     * The label is placed in a PDF string literal, so it must not contain a parenthesis or backslash.
     */
    static byte[] densePagePdf(String label) {
        StringBuilder ops = new StringBuilder("BT /F1 11 Tf 50 740 Td 21 TL ");
        if (!label.isEmpty()) {
            ops.append("(").append(label).append(") Tj T* ");
        }
        for (int i = 1; i <= 32; i++) {
            ops.append("(Line ").append(i)
               .append(": the quick brown fox jumps over the lazy dog while the committee argues.) Tj T* ");
        }
        String content = ops.append("ET").toString();
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + content.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + content + "\nendstream");
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) {
            pdf.append(String.format(Locale.ROOT, "%010d 00000 n \n", off));
        }
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
           .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    // --- reporting ---------------------------------------------------------------------------

    private void report() {
        System.out.println();
        long aloneFailed = alone.stream().filter(c -> !c.ok()).count();
        long togetherFailed = together.stream().filter(c -> !c.ok()).count();

        System.out.printf("%-22s %-8s %-10s %-10s%n", "", "calls", "failed", "median s");
        line("marker alone", alone, "marker");
        line("yomitoku alone", alone, "yomitoku");
        line("marker together", together, "marker");
        line("yomitoku together", together, "yomitoku");

        double aloneW = median(aloneWall);
        double togetherW = median(togetherWall);
        double longerOfTwo = Math.max(median(times(together, "marker")), median(times(together, "yomitoku")));
        System.out.println();
        System.out.printf("wall time for one page: %.1fs one after the other, %.1fs at the same time%n",
                aloneW, togetherW);
        System.out.printf("the slower of the overlapping pair took %.1fs, so the pair's wall time is %s%n",
                longerOfTwo, togetherW <= longerOfTwo * 1.15 ? "that one call: they really overlap"
                        : "more than that: the GPU serialises part of the work");

        for (Call c : together) {
            if (!c.ok()) {
                System.out.println("failure while overlapping: " + c.engine() + " -> " + c.detail());
            }
        }
        for (Call c : alone) {
            if (!c.ok()) {
                System.out.println("failure while alone:       " + c.engine() + " -> " + c.detail());
            }
        }

        System.out.println();
        if (togetherFailed > 0 && aloneFailed == 0) {
            System.out.println("ConcurrentOcrOnOneNodeE2E: the two engines CANNOT share this node -- "
                    + togetherFailed + " of " + together.size() + " overlapping calls failed and every call alone succeeded");
            System.exit(1);
        }
        if (togetherFailed > 0) {
            System.out.println("ConcurrentOcrOnOneNodeE2E: calls failed in both modes ("
                    + aloneFailed + " alone, " + togetherFailed + " overlapping) -- the node is unwell, not merely busy");
            System.exit(1);
        }
        System.out.println("ConcurrentOcrOnOneNodeE2E: PASSED -- the two engines share this node without failing ("
                + (alone.size() + together.size()) + " calls)");
    }

    private void line(String label, List<Call> calls, String engine) {
        List<Call> mine = calls.stream().filter(c -> c.engine().equals(engine)).toList();
        long failed = mine.stream().filter(c -> !c.ok()).count();
        System.out.printf("%-22s %-8d %-10d %-10.1f%n", label, mine.size(), failed,
                median(mine.stream().map(Call::seconds).toList()));
    }

    private static List<Double> times(List<Call> calls, String engine) {
        return calls.stream().filter(c -> c.engine().equals(engine)).map(Call::seconds).toList();
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
        return flat.length() > 160 ? flat.substring(0, 160) + "..." : flat;
    }
}
