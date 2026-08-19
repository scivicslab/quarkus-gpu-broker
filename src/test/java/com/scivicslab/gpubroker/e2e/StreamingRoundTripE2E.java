package com.scivicslab.gpubroker.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Synchronous streaming (scenario 2 of {@code E2ETestPlan_260810_oo01}): a
 * chat-completions request with {@code stream:true} against a real,
 * already-running vLLM queue arrives as multiple SSE chunks, not one blob.
 * Uses {@code java.net.http.HttpClient} directly (not RestAssured) so the
 * response can be inspected line-by-line as it streams in.
 */
class StreamingRoundTripE2E extends GpuBrokerE2EBase {

    private static final String QUEUE = "vllm-google-gemma-4-26B-A4B-it";

    public static void main(String[] args) throws Exception {
        new StreamingRoundTripE2E().run();
    }

    void run() throws Exception {
        System.out.println("--- StreamingRoundTripE2E ---");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/queue/" + QUEUE))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"model":"google/gemma-4-26B-A4B-it","max_tokens":30,"stream":true,
                         "messages":[{"role":"user","content":"count from one to ten"}]}
                        """))
                .build();

        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        List<String> dataLines = response.body()
                .filter(line -> line.startsWith("data: "))
                .collect(Collectors.toList());

        require(response.statusCode() == 200, "expected 200, got " + response.statusCode());
        require(dataLines.size() > 1,
                "expected multiple SSE chunks (streaming), got " + dataLines.size() + " data line(s)");

        LOG.info("Streaming chunks received: " + dataLines.size());
        System.out.println("StreamingRoundTripE2E: PASSED");
    }
}
