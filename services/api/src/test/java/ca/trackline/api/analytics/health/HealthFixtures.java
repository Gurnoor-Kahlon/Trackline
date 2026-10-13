package ca.trackline.api.analytics.health;

import java.time.Duration;

/** The algorithm's documented thresholds, for tests that do not care to spell them out. */
final class HealthFixtures {

    private HealthFixtures() {
    }

    static HealthProperties defaults() {
        return new HealthProperties(
                0.25, 0.5, 0.75, 1.3, 1.8, 2.5, 0.5, 2, 3, 5, 3,
                Duration.ofSeconds(120), 0.3, 200,
                Duration.ofMinutes(15), Duration.ofMinutes(3), 3, 3, 25, 1.0);
    }
}
