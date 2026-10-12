package ca.trackline.api.stream;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * What the streams are doing, for watching rather than for logs.
 *
 * <p>Evictions are the figure to watch. A client dropped for being too slow is
 * invisible to it beyond a reconnect, so without a count a slow network or a
 * misbehaving consumer would degrade the stream with nothing to show for it.
 */
@Component
class StreamMetrics {

    private final MeterRegistry registry;

    StreamMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void bindSubscriberGauge(String channel, Supplier<Number> count) {
        Gauge.builder("trackline.stream.subscribers", count).tag("channel", channel).register(registry);
    }

    void eventSent(String channel, String event) {
        registry.counter("trackline.stream.events.sent", "channel", channel, "event", event).increment();
    }

    void bytesSent(String channel, long bytes) {
        registry.counter("trackline.stream.bytes.sent", "channel", channel).increment(bytes);
    }

    void evicted(String channel) {
        registry.counter("trackline.stream.evictions", "channel", channel).increment();
    }

    void resumed(String channel, String how) {
        registry.counter("trackline.stream.connections", "channel", channel, "start", how).increment();
    }
}
