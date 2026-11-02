package com.scivicslab.gpubroker.capacity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Reads {@link ServerRun} from a server's metrics path. Empty when the server does not answer, or
 * answers without the lines a {@code ServerRun} needs: the survey then decides the slots without
 * it, as before.
 */
public class ServerRunReader {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public Optional<ServerRun> read(String address, String metricsPath) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address + metricsPath))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            return ServerRun.parse(response.body());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
