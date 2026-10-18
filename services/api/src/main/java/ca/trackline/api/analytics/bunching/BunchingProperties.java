package ca.trackline.api.analytics.bunching;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The bunching detector's cut-points and the rules for following a bunch from cycle to cycle.
 *
 * <p>Like the route-health bands these are documented starting judgements, not a calibration:
 * round numbers that say what "close" and "a real gap" mean, with no ground truth of known
 * bunching to fit them against. They are properties so recalibrating changes configuration.
 *
 * @param closeRatio        two neighbouring vehicles are bunched when the gap between them is
 *                          below this fraction of the expected spacing
 * @param serviceGapRatio   a bunch is only reported if a gap elsewhere on the route is above
 *                          this multiple of the expected spacing. Evenly spaced but dense
 *                          service is not bunching, and this rule is what keeps it so
 * @param severeGapRatio    a service gap above this multiple makes a bunch severe
 * @param severeClusterSize this many vehicles in one bunch make it severe
 * @param minVehicles       fewest vehicles on a direction for which a bunch can be judged against
 *                          the timetable: with fewer, no gap elsewhere can be established
 * @param minVehiclesWithoutTimetable fewest when there is no timetable and the vehicles' own
 *                          average is the yardstick. With three vehicles one close pair forces the
 *                          other gap to about twice the average, so a bunch would be found
 *                          whenever a pair was close, by arithmetic and not by evidence
 * @param minSharedVehicles an open bunch is the same bunch as a new one when they share at least
 *                          this many vehicles, so a vehicle joining or leaving does not make a
 *                          new event
 * @param cyclesToResolve   a bunch is resolved after this many consecutive analyses without it,
 *                          so a bunch flickering across a threshold is one event, not hundreds
 * @param cyclesToConfirm   a new pack is recorded only after being seen on this many consecutive
 *                          analyses. Measured on the live feed, most packs first recorded lasted one
 *                          or two analyses: vehicles passing at a stop, not bunching. It is recorded
 *                          from its first sighting, so the wait costs no time
 */
@Validated
@ConfigurationProperties(prefix = "trackline.analytics.bunching")
public record BunchingProperties(
        @Positive double closeRatio,
        @Positive double serviceGapRatio,
        @Positive double severeGapRatio,
        @Positive int severeClusterSize,
        @Positive int minVehicles,
        @Positive int minVehiclesWithoutTimetable,
        @Positive int minSharedVehicles,
        @Positive int cyclesToResolve,
        @Positive int cyclesToConfirm) {

    public BunchingProperties {
        if (!(closeRatio < 1.0)) {
            throw new IllegalArgumentException("closeRatio must be below 1: a gap at the expected spacing is not close");
        }
        if (!(serviceGapRatio > 1.0 && serviceGapRatio < severeGapRatio)) {
            throw new IllegalArgumentException("Gap ratios must rise: 1 < serviceGapRatio < severeGapRatio");
        }
        if (minVehiclesWithoutTimetable < minVehicles) {
            throw new IllegalArgumentException("minVehiclesWithoutTimetable cannot be below minVehicles");
        }
        if (minSharedVehicles > minVehicles) {
            throw new IllegalArgumentException("minSharedVehicles cannot exceed minVehicles");
        }
    }
}
