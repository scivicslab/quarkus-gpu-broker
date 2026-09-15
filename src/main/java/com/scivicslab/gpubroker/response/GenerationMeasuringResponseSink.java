package com.scivicslab.gpubroker.response;

import java.util.function.Consumer;

import com.scivicslab.gpubroker.model.GenerationMeasurement;
import com.scivicslab.gpubroker.model.ResponseSink;

/**
 * A {@link ResponseSink} that forwards everything and times what passes through it
 * ({@code GenerationRateOnTheStatusPage_260915_oo01}).
 *
 * <p>One measurement is handed on when the response ends, however it ends. A response that failed
 * before it produced anything still says how long it waited, which is the figure that explains a
 * queue with one slot.</p>
 */
public final class GenerationMeasuringResponseSink implements ResponseSink {

    private final ResponseSink delegate;
    private final String queueName;
    private final Consumer<GenerationMeasurement> report;
    private final SseContentScanner scanner = new SseContentScanner();

    private final long arrived = System.currentTimeMillis();
    private long dispatched;
    private long firstText;
    private long lastText;
    private String address;
    private boolean reported;

    /**
     * @param delegate  where everything is forwarded, unchanged and in order
     * @param queueName the queue this generation ran on
     * @param report    told once, when the response ends
     */
    public GenerationMeasuringResponseSink(ResponseSink delegate, String queueName,
                                           Consumer<GenerationMeasurement> report) {
        this.delegate = delegate;
        this.queueName = queueName;
        this.report = report;
    }

    @Override
    public void dispatched() {
        dispatched = System.currentTimeMillis();
        delegate.dispatched();
    }

    @Override
    public void servedBy(String endpointAddress) {
        // A job that was requeued onto another endpoint is attributed to the one that answered.
        address = endpointAddress;
        delegate.servedBy(endpointAddress);
    }

    @Override
    public void start(String contentType) {
        delegate.start(contentType);
    }

    @Override
    public void emit(byte[] chunk) {
        delegate.emit(chunk);
        long before = scanner.events();
        scanner.feed(chunk, (source, from, to) -> { });
        if (scanner.events() > before) {
            long now = System.currentTimeMillis();
            if (firstText == 0) {
                firstText = now;
            }
            lastText = now;
        }
    }

    @Override
    public boolean stopRequested() {
        return delegate.stopRequested();
    }

    @Override
    public void complete() {
        hand();
        delegate.complete();
    }

    @Override
    public void fail(Throwable cause) {
        hand();
        delegate.fail(cause);
    }

    /** Reports once. A sink can be failed after it has been completed; the first ending is the one. */
    private void hand() {
        if (reported) {
            return;
        }
        reported = true;
        long queued = dispatched == 0 ? 0 : dispatched - arrived;
        long first = dispatched == 0 || firstText == 0 ? 0 : firstText - dispatched;
        long decode = firstText == 0 ? 0 : lastText - firstText;
        report.accept(new GenerationMeasurement(queueName, address, queued, first, decode,
                scanner.events()));
    }
}
