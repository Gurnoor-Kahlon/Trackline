package ca.trackline.api.analytics.progress;

import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Counters for how vehicles were placed, and how long placing them took.
 *
 * <p>The status tag is the whole point. A rising share of unknown-direction or
 * off-route vehicles is the first visible symptom of a feed that has changed or a
 * shape set that has gone stale, and it is invisible in any single cycle.
 */
@Component
public class ProgressMetrics {

    private static final String VEHICLES = "trackline.analytics.projection.vehicles";
    private static final String UNKNOWN = "trackline.analytics.projection.unknown";
    private static final String DURATION = "trackline.analytics.projection.duration";
    private static final String FAILURES = "trackline.analytics.projection.failures";

    private final MeterRegistry registry;

    public ProgressMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T time(Supplier<T> work) {
        return Timer.builder(DURATION).register(registry).record(work);
    }

    public void record(RouteProgressSnapshot snapshot) {
        for (Map.Entry<ProjectionStatus, Long> entry : snapshot.countsByStatus().entrySet()) {
            registry.counter(VEHICLES, "status", entry.getKey().name()).increment(entry.getValue());
        }
        for (Map.Entry<UnknownReason, Long> entry : snapshot.unknownReasons().entrySet()) {
            registry.counter(UNKNOWN, "reason", entry.getKey().name()).increment(entry.getValue());
        }
    }

    /** A cycle whose projection threw. Its observations are still stored, unprojected. */
    public void recordFailure() {
        registry.counter(FAILURES).increment();
    }
}
