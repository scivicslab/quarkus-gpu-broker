package com.scivicslab.gpubroker.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;

import com.scivicslab.gpubroker.model.Job;

/**
 * Forwards a job to one upstream vLLM over HTTP and relays its response.
 *
 * <p>Runs on the calling {@code NodeActor}'s virtual thread: it POSTs the OpenAI
 * request body to {@code <url>/v1/chat/completions}, and once the response
 * headers arrive it reports the upstream {@code Content-Type} on the job (so the
 * proxy can mirror it) and {@link StreamRelay#pump pumps} the lazily-read
 * response lines into the job's sink until the stream ends. A connection failure
 * or a 5xx status raises {@link UpstreamException} <em>before</em> the
 * Content-Type is reported, so the {@code NodeActor} re-submits the job (whose
 * Content-Type future is still pending) and a healthy node serves it.
 */
public final class HttpUpstreamLlmClient implements UpstreamLlmClient {

    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public void send(String url, Job job) throws UpstreamException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        job.requestBody() == null ? "" : job.requestBody()))
                .build();
        try {
            HttpResponse<Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 500) {
                throw new UpstreamException("upstream " + url + " returned " + response.statusCode());
            }
            // Headers are in: mirror the upstream Content-Type before streaming the body.
            job.completeContentType(
                    response.headers().firstValue("content-type").orElse("application/json"));
            try (Stream<String> lines = response.body()) {
                StreamRelay.pump(lines.iterator(), job);   // lazily read → relayed live
            }
        } catch (IOException e) {
            throw new UpstreamException("upstream " + url + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("upstream " + url + " interrupted", e);
        }
    }
}
