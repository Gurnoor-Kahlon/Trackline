package ca.trackline.api.analytics.spacing;

import java.util.List;

import ca.trackline.api.analytics.health.ReasonCode;


/**
 * What a route's spacing is measured against.
 *
 * <p>Health and bunching must agree on this or they would contradict each other about the
 * same gap, so it is decided in one place. With a timetable comparison it is the spacing
 * the timetable implies, the scheduled headway times the measured speed. Without one it is the
 * vehicles' own average gap, which can reveal one hole among small gaps and cannot reveal a
 * route whose every gap is too wide.
 *
 * @param scheduledMetres  the timetable's spacing as a distance, or null when it could not be
 *                         worked out
 * @param shortfall        why not, or null when it could
 */
public record SpacingYardstick(Double scheduledMetres, ReasonCode shortfall) {

    /**
     * @param covered   whether the imported timetable describes today
     * @param headwaySeconds the timetable's spacing for this hour, or null
     * @param speedMetresPerSecond the route's measured speed, or null
     */
    public static SpacingYardstick of(boolean covered, Double headwaySeconds, Double speedMetresPerSecond) {
        if (!covered) {
            return new SpacingYardstick(null, ReasonCode.NO_SCHEDULE_COVERAGE);
        }
        if (headwaySeconds == null) {
            return new SpacingYardstick(null, ReasonCode.NO_SCHEDULED_HEADWAY);
        }
        if (speedMetresPerSecond == null) {
            return new SpacingYardstick(null, ReasonCode.SPEED_UNKNOWN);
        }
        return new SpacingYardstick(headwaySeconds * speedMetresPerSecond, null);
    }

    public SpacingBasis basis() {
        return scheduledMetres == null ? SpacingBasis.OBSERVED : SpacingBasis.SCHEDULE;
    }

    /** The distance gaps are compared with: the timetable's if there is one, else the gaps' own mean. */
    public double metres(List<Double> gaps) {
        return scheduledMetres != null ? scheduledMetres : mean(gaps);
    }

    public static double mean(List<Double> gaps) {
        return gaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
}
