package com.scivicslab.gpubroker.config;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

/** The Whisper (yt-dlp + faster-whisper) video transcript server's endpoint. */
@ApplicationScoped
public class WhisperTranscriptProbe implements EndpointProbe {

    @Override
    public int conventionalPort() {
        return 8003;
    }

    @Override
    public String probePath() {
        return "/health";   // "/" 404s on this FastAPI app -- confirmed by direct verification
    }

    @Override
    public String requestPath() {
        return "/transcript";
    }

    @Override
    public int defaultMaxConcurrency() {
        return 1;   // one GPU decoding a video at a time; a video can take minutes, not measured beyond 1
    }

    @Override
    public Optional<String> deriveQueueName(String probeResponseBody) {
        return Optional.of("whisper-transcript");
    }
}
