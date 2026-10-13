package ca.trackline.api.analytics.health;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * How many routes are in each state, and how long working that out took.
 *
 * <p>A gauge per state rather than a counter, because the question is "how many routes are
 * unstable now", and a feed or a threshold going wrong shows first as that distribution
 * jumping: every route turning disrupted at once is a bug, not a bad day.
 */
@Component
public class HealthAnalysisMetrics {

    private static final String DURATION = "trackline.analytics.health.duration";
    private static final String FAILURES = "trackline.analytics.health.failures";
    private static final String ROUTES = "trackline.analytics.health.routes";

    private final MeterRegistry registry;
    private final Map<RouteHealthState, AtomicInteger> routesByState = new EnumMap<>(RouteHealthState.class);

    public HealthAnalysisMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (RouteHealthState state : RouteHealthState.values()) {
            AtomicInteger count = new AtomicInteger();
            routesByState.put(state, count);
            registry.gauge(ROUTES, List.of(Tag.of("state", state.name())), count);
        }
    }

    public <T> T time(Supplier<T> work) {
        return Timer.builder(DURATION).register(registry).record(work);
    }

    public void record(HealthSnapshot snapshot) {
        for (RouteHealthState state : RouteHealthState.values()) {
            routesByState.get(state).set(0);
        }
        for (RouteHealth route : snapshot.routes()) {
            routesByState.get(route.state()).incrementAndGet();
        }
    }

    /** An analysis that threw. The cycle's readings are unaffected. */
    public void recordFailure() {
        registry.counter(FAILURES).increment();
    }
}
