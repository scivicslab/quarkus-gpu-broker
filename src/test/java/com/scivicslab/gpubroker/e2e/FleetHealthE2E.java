package com.scivicslab.gpubroker.e2e;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.restassured.RestAssured;

/**
 * Every queue the running broker knows, exercised with real work: each endpoint directly, then the
 * queue through the broker. A queue's kind decides the work, and the work is sized so that a
 * healthy endpoint is busy for seconds, not minutes -- enough to show that the model is loaded,
 * the GPU computes, and the answer has the expected shape, but short enough to run before a
 * release or after a machine was touched.
 *
 * <ul>
 *   <li>{@code vllm-*} (any OpenAI-compatible chat endpoint: vLLM, TensorFold, Strata): several
 *       requests at once, each a few hundred tokens of generated text with thinking off; the
 *       reply must carry text, and tokens per second is reported per endpoint</li>
 *   <li>{@code embedding-e5large}: a batch of sentences; every vector must have 1024 dimensions</li>
 *   <li>{@code whisper-transcript}: a 19-second public video; the transcript must mention its
 *       subject (an elephant)</li>
 *   <li>{@code marker-ocr}, {@code yomitoku-ocr}: a one-page PDF written here with a number on
 *       it; the OCR text must contain that number</li>
 * </ul>
 *
 * <p>The test takes the broker's own picture of the fleet ({@code GET /queues}) as the list of
 * what to check, so a machine that was added or moved is covered without editing this class. An
 * endpoint the broker lists as DOWN is still exercised and reported as a failure: the point of the
 * run is to say what works now, not what the broker believes.
 *
 * <p>Plain Java with {@code main()}, no JUnit, per {@code TestingStandard_260404_oo01} section 3.
 * Run from the repository:
 * <pre>
 *   mvn test-compile exec:java -Dexec.mainClass=com.scivicslab.gpubroker.e2e.FleetHealthE2E \
 *       -Dexec.classpathScope=test -De2e.base.url=http://192.168.5.21:30805
 * </pre>
 * Options: {@code -De2e.chat.parallel=4} requests at once per chat endpoint,
 * {@code -De2e.chat.tokens=256} tokens each, {@code -De2e.skip=whisper-transcript,marker-ocr}
 * to leave kinds out, {@code -De2e.only=yomitoku-ocr} to run one queue, {@code -De2e.whisper.url=...}
 * another short video.
 */
class FleetHealthE2E extends GpuBrokerE2EBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final int CHAT_PARALLEL = Integer.getInteger("e2e.chat.parallel", 4);
    private static final int CHAT_TOKENS = Integer.getInteger("e2e.chat.tokens", 256);
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(Long.getLong("e2e.chat.timeout", 300));
    private static final Duration SLOW_TIMEOUT = Duration.ofSeconds(Long.getLong("e2e.slow.timeout", 600));
    private static final String WHISPER_URL = System.getProperty("e2e.whisper.url",
            "https://www.youtube.com/watch?v=jNQXAC9IVRw");   // "Me at the zoo", 19 s, about elephants
    private static final Set<String> SKIP = Set.of(System.getProperty("e2e.skip", "").split(","));
    private static final String ONLY = System.getProperty("e2e.only", "");

    /** The number written on the OCR test page; digits survive OCR where letters may not. */
    private static final String OCR_MARK = "4817263950";

    private static final String[] CHAT_PROMPTS = {
        "Explain in about 200 words how a hash table handles collisions.",
        "Explain in about 200 words why TCP uses a three-way handshake.",
        "Explain in about 200 words what a garbage collector does.",
        "Explain in about 200 words how public-key encryption lets two strangers share a secret.",
        "Explain in about 200 words what a database index is for.",
        "Explain in about 200 words how DNS turns a name into an address.",
        "Explain in about 200 words what a race condition is.",
        "Explain in about 200 words how a compiler differs from an interpreter.",
    };

    /** One line of the report: what was exercised, whether it passed, and what was measured. */
    record Row(String queue, String target, boolean ok, double seconds, String detail) {
    }

    private final List<Row> rows = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        new FleetHealthE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- FleetHealthE2E --- broker " + BASE_URL);
        List<Map<String, Object>> queues = RestAssured.given().baseUri(BASE_URL)
                .when().get("/queues")
                .then().statusCode(200)
                .extract().jsonPath().getList("");
        require(!queues.isEmpty(), "GET /queues lists no queue at all");

        for (Map<String, Object> queue : queues) {
            String name = (String) queue.get("name");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> endpoints = (List<Map<String, Object>>) queue.get("endpoints");
            if (SKIP.contains(name) || (!ONLY.isEmpty() && !ONLY.equals(name))) {
                System.out.printf("%-42s skipped (-De2e.skip / -De2e.only)%n", name);
                continue;
            }
            for (Map<String, Object> endpoint : endpoints) {
                String address = (String) endpoint.get("address");
                String health = String.valueOf(endpoint.get("health"));
                exercise(name, address + " (broker says " + health + ")", "http://" + address, false);
            }
            exercise(name, "via broker", BASE_URL + "/queue/" + name, true);
        }
        report();
    }

    /** Runs the work that fits {@code queue} against {@code base}, catching every failure into a row. */
    private void exercise(String queue, String target, String base, boolean viaBroker) {
        long t0 = System.nanoTime();
        try {
            String detail;
            if (queue.startsWith("vllm-")) {
                detail = chat(base, viaBroker);
            } else if (queue.startsWith("embedding")) {
                detail = embedding(base, viaBroker);
            } else if (queue.equals("whisper-transcript")) {
                detail = whisper(base, viaBroker);
            } else if (queue.equals("marker-ocr")) {
                detail = ocr(base, viaBroker, "/marker/upload", Map.of("page_range", "0", "output_format", "markdown"));
            } else if (queue.equals("yomitoku-ocr")) {
                detail = ocr(base, viaBroker, "/ocr/markdown", Map.of("page", "0"));
            } else {
                detail = "no exercise defined for this kind of queue";
                rows.add(new Row(queue, target, false, elapsed(t0), detail));
                System.out.printf("%-42s %-40s FAIL  %s%n", queue, target, detail);
                return;
            }
            rows.add(new Row(queue, target, true, elapsed(t0), detail));
            System.out.printf("%-42s %-40s ok    %5.1fs  %s%n", queue, target, elapsed(t0), detail);
        } catch (Exception | AssertionError e) {
            String detail = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replace('\n', ' ');
            rows.add(new Row(queue, target, false, elapsed(t0), detail));
            System.out.printf("%-42s %-40s FAIL  %5.1fs  %s%n", queue, target, elapsed(t0),
                    detail.length() > 200 ? detail.substring(0, 200) : detail);
        }
    }

    // --- chat ---------------------------------------------------------------------------------

    /**
     * {@link #CHAT_PARALLEL} requests at once, each asking for {@link #CHAT_TOKENS} tokens with
     * thinking off. Directly, the endpoint's own {@code /v1/models} names the model; through the
     * broker the queue is addressed and the model field is what the first endpoint advertises.
     */
    private String chat(String base, boolean viaBroker) throws Exception {
        String model = viaBroker ? modelOfQueue(base) : firstModelId(base + "/v1/models");
        String url = viaBroker ? base : base + "/v1/chat/completions";
        ExecutorService pool = Executors.newFixedThreadPool(CHAT_PARALLEL);
        try {
            List<CompletableFuture<double[]>> futures = new ArrayList<>();
            for (int i = 0; i < CHAT_PARALLEL; i++) {
                String prompt = CHAT_PROMPTS[i % CHAT_PROMPTS.length];
                futures.add(CompletableFuture.supplyAsync(() -> oneChat(url, model, prompt), pool));
            }
            double[] tokPerSec = new double[CHAT_PARALLEL];
            double sumTokens = 0;
            long t0 = System.nanoTime();
            for (int i = 0; i < CHAT_PARALLEL; i++) {
                double[] r = futures.get(i).get(CHAT_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
                tokPerSec[i] = r[0];
                sumTokens += r[1];
            }
            double wall = elapsed(t0);
            Arrays.sort(tokPerSec);
            return String.format(Locale.ROOT, "%d x %d tokens: %.1f tok/s per request (median), %.1f tok/s together",
                    CHAT_PARALLEL, CHAT_TOKENS, tokPerSec[CHAT_PARALLEL / 2], sumTokens / wall);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One chat request; returns {tokens per second, completion tokens}. */
    private double[] oneChat(String url, String model, String prompt) {
        try {
            String body = MAPPER.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", CHAT_TOKENS,
                    "temperature", 0,
                    "stream", false,
                    "chat_template_kwargs", Map.of("enable_thinking", false)));
            long t0 = System.nanoTime();
            HttpResponse<String> resp = post(url, "application/json", body.getBytes(StandardCharsets.UTF_8), CHAT_TIMEOUT);
            double seconds = elapsed(t0);
            require(resp.statusCode() == 200, "HTTP " + resp.statusCode() + ": " + head(resp.body()));
            JsonNode root = MAPPER.readTree(resp.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int completion = root.path("usage").path("completion_tokens").asInt(0);
            require(!content.isBlank(), "empty content: " + head(resp.body()));
            require(completion >= 32, "only " + completion + " completion tokens: " + head(resp.body()));
            return new double[] {completion / seconds, completion};
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The queue's model id: what the broker advertises under {@code /v1/models} for this queue. */
    private String modelOfQueue(String queueUrl) throws Exception {
        String queue = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
        JsonNode models = MAPPER.readTree(get(BASE_URL + "/v1/models").body()).path("data");
        for (JsonNode m : models) {
            String id = m.path("id").asText();
            if (sanitized(id).equals(queue)) {
                return id;
            }
        }
        throw new AssertionError("the broker's /v1/models has no model for queue " + queue);
    }

    /** The broker's queue name for a model id, the same rule as {@code VllmQueueName.of}. */
    private static String sanitized(String modelId) {
        return com.scivicslab.gpubroker.config.VllmQueueName.of(modelId);
    }

    private String firstModelId(String modelsUrl) throws Exception {
        JsonNode root = MAPPER.readTree(get(modelsUrl).body());
        String id = root.path("data").path(0).path("id").asText("");
        require(!id.isEmpty(), "no model id in " + modelsUrl);
        return id;
    }

    // --- embedding ----------------------------------------------------------------------------

    private String embedding(String base, boolean viaBroker) throws Exception {
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            inputs.add("Sentence " + i + " of the fleet health check: " + CHAT_PROMPTS[i % CHAT_PROMPTS.length]);
        }
        String body = MAPPER.writeValueAsString(Map.of("model", "e5-large", "input", inputs));
        String url = viaBroker ? base : base + "/v1/embeddings";
        HttpResponse<String> resp = post(url, "application/json", body.getBytes(StandardCharsets.UTF_8), CHAT_TIMEOUT);
        require(resp.statusCode() == 200, "HTTP " + resp.statusCode() + ": " + head(resp.body()));
        JsonNode data = MAPPER.readTree(resp.body()).path("data");
        require(data.size() == inputs.size(), "expected " + inputs.size() + " vectors, got " + data.size());
        int dims = data.path(0).path("embedding").size();
        require(dims == 1024, "expected 1024-dimensional vectors, got " + dims);
        return inputs.size() + " vectors of " + dims + " dimensions";
    }

    // --- whisper ------------------------------------------------------------------------------

    private String whisper(String base, boolean viaBroker) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("url", WHISPER_URL));
        String url = viaBroker ? base : base + "/transcript";
        HttpResponse<String> resp = post(url, "application/json", body.getBytes(StandardCharsets.UTF_8), SLOW_TIMEOUT);
        require(resp.statusCode() == 200, "HTTP " + resp.statusCode() + ": " + head(resp.body()));
        JsonNode root = MAPPER.readTree(resp.body());
        require(root.path("success").asBoolean(false), "transcript server reported failure: " + head(resp.body()));
        JsonNode segments = root.path("segments");
        StringBuilder text = new StringBuilder();
        for (JsonNode s : segments) {
            text.append(s.path("text").asText()).append(' ');
        }
        require(text.toString().toLowerCase(Locale.ROOT).contains("elephant"),
                "transcript does not mention the elephants: " + head(text.toString()));
        return segments.size() + " segments, text mentions the elephants";
    }

    // --- OCR ----------------------------------------------------------------------------------

    private String ocr(String base, boolean viaBroker, String path, Map<String, String> fields) throws Exception {
        byte[] pdf = onePagePdf("GPU BROKER HEALTH CHECK\n" + OCR_MARK);
        String boundary = "----FleetHealth" + System.nanoTime();
        byte[] body = multipart(boundary, fields, "file", "page.pdf", "application/pdf", pdf);
        String url = viaBroker ? base : base + path;
        HttpResponse<String> resp = post(url, "multipart/form-data; boundary=" + boundary, body, SLOW_TIMEOUT);
        require(resp.statusCode() == 200, "HTTP " + resp.statusCode() + ": " + head(resp.body()));
        String flat = resp.body().replaceAll("[\\s\\-_]", "");
        require(flat.contains(OCR_MARK), "OCR text lacks the number " + OCR_MARK + ": " + head(resp.body()));
        return "page read, the number " + OCR_MARK + " came back";
    }

    /**
     * A one-page PDF with {@code text} in large Helvetica, written by hand: a catalog, pages, one
     * page, a font, a content stream, and a cross-reference table with correct byte offsets.
     */
    static byte[] onePagePdf(String text) {
        // Two lines, 32 pt: a page-rendering OCR (YomiToku) reads only what lies inside the 612 pt
        // width, and one 40 pt line of the whole text ran off the right edge and lost the number.
        String[] lines = text.split("\\n");
        StringBuilder ops = new StringBuilder("BT /F1 32 Tf 40 700 Td 40 TL ");
        for (String line : lines) {
            ops.append("(").append(line.replace("(", "\\(").replace(")", "\\)")).append(") Tj T* ");
        }
        String content = ops.append("ET").toString();
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>",
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

    static byte[] multipart(String boundary, Map<String, String> fields, String fileField, String fileName,
                            String fileType, byte[] file) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                    + e.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\""
                + fileName + "\"\r\nContent-Type: " + fileType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // --- HTTP and reporting -------------------------------------------------------------------

    private static HttpResponse<String> post(String url, String contentType, byte[] body, Duration timeout)
            throws java.io.IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", contentType)
                .header("X-Job-Priority", "background")   // through the broker: interactive users keep their slots
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String url) throws java.io.IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static double elapsed(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    private static String head(String s) {
        String flat = s == null ? "" : s.replace('\n', ' ');
        return flat.length() > 160 ? flat.substring(0, 160) + "..." : flat;
    }

    private void report() {
        long failed = rows.stream().filter(r -> !r.ok()).count();
        System.out.println();
        System.out.printf("%-42s %-40s %-6s %7s  %s%n", "queue", "target", "result", "seconds", "detail");
        for (Row r : rows) {
            System.out.printf("%-42s %-40s %-6s %7.1f  %s%n", r.queue(), r.target(), r.ok() ? "ok" : "FAIL",
                    r.seconds(), r.detail().length() > 110 ? r.detail().substring(0, 110) + "..." : r.detail());
        }
        System.out.println();
        if (failed > 0) {
            System.out.println("FleetHealthE2E: FAILED (" + failed + " of " + rows.size() + " checks)");
            System.exit(1);
        }
        System.out.println("FleetHealthE2E: PASSED (" + rows.size() + " checks)");
    }
}
