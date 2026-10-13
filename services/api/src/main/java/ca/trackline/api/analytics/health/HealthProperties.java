package ca.trackline.api.analytics.health;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every cut-point and window the route-health algorithm uses, in one place.
 *
 * <p>These are the algorithm's judgement calls and they are written down as such. The
 * variation and gap bands are round, documented starting points taken from the
 * conventional transit-reliability reading of the coefficient of variation of headway,
 * not fitted to anything; the windows and counts are set from what the feed does.
 * {@code docs/route-health.md} states which is which. They are properties, not constants,
 * so recalibrating once there is history to calibrate against touches configuration and
 * not the algorithm.
 *
 * <p>The three bands for each measure must rise: a route cannot be unstable at a lower
 * variation than it is merely normal.
 *
 * @param excellentCv         variation of spacing at or below which, with the gap ratio, a
 *                            route is excellent
 * @param unstableCv          variation above which a route is unstable
 * @param majorCv             variation above which a route is badly disrupted
 * @param excellentGapRatio   largest gap, as a multiple of the expected spacing, at or
 *                            below which a route can still be excellent
 * @param unstableGapRatio    gap ratio above which a route is unstable
 * @param majorGapRatio       gap ratio above which a route is badly disrupted
 * @param majorSupplyRatio    share of the scheduled number of vehicles below which a route
 *                            is badly disrupted
 * @param minVehicles         fewest vehicles from which spacing can be read at all
 * @param mediumConfidenceVehicles fewest vehicles for more than low confidence; below it a
 *                            verdict rests on a single gap, and with no timetable to compare
 *                            against there is no verdict at all
 * @param highConfidenceVehicles vehicles from which, with a schedule to compare against,
 *                            a verdict is trusted fully
 * @param minGapsForVariation fewest gaps for a variation figure. With two gaps the
 *                            coefficient of variation is only their ratio and cannot exceed
 *                            one, so it saturates and says nothing the ratio does not
 * @param staleAfter          a vehicle whose own report is older than this is excluded
 *                            from spacing and counted as stale
 * @param staleFractionForLowConfidence share of stale vehicles above which a verdict is
 *                            low-confidence
 * @param terminalZoneMetres  vehicles this close to either end of a (non-loop) route are
 *                            at a terminal, where layovers park, and are left out of spacing
 * @param speedWindow         how far back readings are kept to measure a route's speed
 * @param minSpeedSpan        shortest stretch of readings a vehicle needs to give a speed
 * @param minSpeedReadings    fewest readings a vehicle needs to give a speed
 * @param minSpeedVehicles    fewest vehicles whose speeds a route's speed is taken over
 * @param maxPlausibleSpeedMetresPerSecond a measured speed above this is a projection
 *                            error, not a bus, and is discarded
 * @param minPlausibleSpeedMetresPerSecond a route whose median speed is below this has
 *                            vehicles that are parked, not travelling; the figure would turn a
 *                            timetable spacing into a few dozen metres, so no speed is reported
 */
@Validated
@ConfigurationProperties(prefix = "trackline.analytics.health")
public record HealthProperties(
        @PositiveOrZero double excellentCv,
        @Positive double unstableCv,
        @Positive double majorCv,
        @Positive double excellentGapRatio,
        @Positive double unstableGapRatio,
        @Positive double majorGapRatio,
        @Positive double majorSupplyRatio,
        @Positive int minVehicles,
        @Positive int mediumConfidenceVehicles,
        @Positive int highConfidenceVehicles,
        @Positive int minGapsForVariation,
        @NotNull Duration staleAfter,
        @Positive double staleFractionForLowConfidence,
        @PositiveOrZero double terminalZoneMetres,
        @NotNull Duration speedWindow,
        @NotNull Duration minSpeedSpan,
        @Positive int minSpeedReadings,
        @Positive int minSpeedVehicles,
        @Positive double maxPlausibleSpeedMetresPerSecond,
        @Positive double minPlausibleSpeedMetresPerSecond) {

    public HealthProperties {
        if (!(excellentCv < unstableCv && unstableCv < majorCv)) {
            throw new IllegalArgumentException("Variation bands must rise: excellent < unstable < major");
        }
        if (!(excellentGapRatio < unstableGapRatio && unstableGapRatio < majorGapRatio)) {
            throw new IllegalArgumentException("Gap-ratio bands must rise: excellent < unstable < major");
        }
        if (minVehicles > mediumConfidenceVehicles || mediumConfidenceVehicles > highConfidenceVehicles) {
            throw new IllegalArgumentException(
                    "Vehicle counts must not fall: minVehicles <= mediumConfidenceVehicles <= highConfidenceVehicles");
        }
    }
}
