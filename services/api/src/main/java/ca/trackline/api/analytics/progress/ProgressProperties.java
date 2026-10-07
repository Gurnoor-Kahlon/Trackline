package ca.trackline.api.analytics.progress;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Thresholds for placing a vehicle on its route.
 *
 * <p>Each default is set from a measurement over real TTC data rather than chosen
 * for roundness; the configured values carry the evidence in their comments.
 * They are properties rather than constants so a different network, or a better
 * measurement, can change them without touching the algorithm.
 *
 * @param maxOffsetMetres        a vehicle further than this from every shape of its
 *                               route is off-route, and is not placed at all
 * @param bearingToleranceDegrees largest angle between a vehicle's heading and a
 *                               shape's direction of travel that still counts as
 *                               agreement
 * @param minMovementMetres      progress along a shape below this is GPS noise, not
 *                               travel, and says nothing about direction
 * @param maxPreviousAge         a previous reading older than this is too stale to
 *                               infer which way a vehicle is moving
 * @param tangentWindowMetres    half-length of the stretch of shape used to read
 *                               its direction of travel at a point
 */
@Validated
@ConfigurationProperties(prefix = "trackline.analytics.progress")
public record ProgressProperties(
        @Positive double maxOffsetMetres,
        @Positive @Max(180) double bearingToleranceDegrees,
        @Positive double minMovementMetres,
        @NotNull Duration maxPreviousAge,
        @Positive double tangentWindowMetres) {
}
