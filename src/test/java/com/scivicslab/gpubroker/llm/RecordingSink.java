package com.scivicslab.gpubroker.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.scivicslab.gpubroker.model.ResponseSink;

/** Test sink that records every relayed chunk in order. */
final class RecordingSink implements ResponseSink {

    private final List<String> received = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void write(String chunk) {
        received.add(chunk);
    }

    List<String> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }
}
