package ca.trackline.api.livestate;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the live-state cache.
 *
 * @param snapshotTtl             how long a written snapshot stays readable
 * @param breakerFailureThreshold how many Redis calls in a row must fail before Redis is left alone for a while
 * @param breakerOpenFor          how long Redis is left alone before one call is let through to see whether it is back
 */
@Validated
@ConfigurationProperties(prefix = "trackline.live-state")
public record LiveStateProperties(Duration snapshotTtl, Integer breakerFailureThreshold, Duration breakerOpenFor) {

    public LiveStateProperties {
        if (snapshotTtl == null) {
            snapshotTtl = Duration.ofMinutes(2);
        }
        if (breakerFailureThreshold == null) {
            breakerFailureThreshold = 3;
        }
        if (breakerFailureThreshold < 1) {
            throw new IllegalArgumentException("trackline.live-state.breaker-failure-threshold must be at least 1");
        }
        if (breakerOpenFor == null) {
            breakerOpenFor = Duration.ofSeconds(10);
        }
        if (breakerOpenFor.isNegative() || breakerOpenFor.isZero()) {
            throw new IllegalArgumentException("trackline.live-state.breaker-open-for must be positive");
        }
    }
}
