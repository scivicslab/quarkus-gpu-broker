package com.scivicslab.gpubroker.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

import com.scivicslab.gpubroker.model.Job;

/**
 * Forwards a job to one real AI service over HTTP and relays its response.
 *
 * <p>Runs on the calling {@code AiServiceEndpoint}'s virtual thread: it POSTs
 * {@code job.request()}'s bytes to {@code address + path}, and once the
 * response headers arrive it calls {@code job.responseSink().start(...)}
 * with the AI service's real {@code Content-Type} before relaying the body
 * chunk by chunk. A connection failure or a 5xx status raises {@link
 * AiServiceCallException} <em>before</em> {@code start} is called, so
 * {@code AiServiceEndpoint.requeue} can safely hand the same job to another
 * endpoint.
 */
public final class HttpAiServiceClient implements AiServiceClient {

    private static final int BUFFER_SIZE = 8192;

    // HTTP/1.1 forced: the default client attempts an h2c (HTTP/2 cleartext) upgrade, which
    // corrupts multipart/form-data bodies sent to uvicorn/FastAPI backends (YomiToku, Marker,
    // embedding all are) — the server sees a malformed request and reports the form field missing.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Override
    public void send(String address, String path, Job job) throws AiServiceCallException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(job.request().bytes()));
        if (job.request().contentType() != null) {
            builder.header("Content-Type", job.request().contentType());
        }

        try {
            HttpResponse<InputStream> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 500) {
                throw new AiServiceCallException(
                        "AiServiceEndpoint " + address + path + " returned " + response.statusCode());
            }
            job.responseSink().start(
                    response.headers().firstValue("content-type").orElse("application/octet-stream"));
            try (InputStream body = response.body()) {
                relay(body, job);
            }
            job.responseSink().complete();
        } catch (IOException e) {
            throw new AiServiceCallException("AiServiceEndpoint " + address + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceCallException("AiServiceEndpoint " + address + path + " interrupted", e);
        }
    }

    private void relay(InputStream body, Job job) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = body.read(buffer)) != -1) {
            job.responseSink().emit(Arrays.copyOf(buffer, read));
        }
    }
}
