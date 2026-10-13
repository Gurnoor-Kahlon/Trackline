package ca.trackline.api.analytics.health;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.SpacingBasis;
import ca.trackline.api.routes.TransitMode;

/**
 * Shapes returned by the route-health endpoints.
 *
 * <p>The verdict always travels with the figures behind it, because the point of the
 * feature is an explanation a rider can check ("spacing varies by 62 %, and the longest
 * gap is 2.1 times the expected one"), not a label to take on trust. Wording is composed
 * by the client from the reason codes and these figures, so it cannot say what they do not.
 */
public final class HealthDtos {

    private HealthDtos() {
    }

    /**
     * @param state            the verdict, or UNKNOWN when there is none
     * @param confidence       how far to trust it
     * @param reasons          why, as codes
     * @param directions       the judgement of each direction that had vehicles
     * @param unplacedVehicles vehicles on the route that could not be placed in a direction
     * @param computedAt       when the analysis ran, or null when none has
     */
    public record RouteHealthView(
            String routeId,
            RouteHealthState state,
            HealthConfidence confidence,
            List<ReasonCode> reasons,
            List<DirectionView> directions,
            int unplacedVehicles,
            Instant computedAt) {

        static RouteHealthView of(RouteHealth health, Instant computedAt) {
            return new RouteHealthView(
                    health.routeId(),
                    health.state(),
                    health.confidence(),
                    health.reasons(),
                    health.directions().stream().map(DirectionView::of).toList(),
                    health.unplacedVehicles(),
                    computedAt);
        }

        /** A route nothing is known about, for the stated reason. */
        static RouteHealthView unknown(String routeId, ReasonCode reason, Instant computedAt) {
            return new RouteHealthView(
                    routeId, RouteHealthState.UNKNOWN, HealthConfidence.NONE, List.of(reason), List.of(), 0,
                    computedAt);
        }
    }

    public record DirectionView(
            short directionId,
            RouteHealthState state,
            HealthConfidence confidence,
            List<ReasonCode> reasons,
            MetricsView metrics) {

        static DirectionView of(DirectionHealth direction) {
            return new DirectionView(
                    direction.directionId(),
                    direction.state(),
                    direction.confidence(),
                    direction.reasons(),
                    MetricsView.of(direction.metrics()));
        }
    }

    /**
     * The figures a verdict was reached from. Null where a figure could not be computed,
     * never zero. Distances are in metres, rounded to a tenth; ratios to a hundredth.
     *
     * @param variation   standard deviation of the gaps over their mean, as a fraction
     *                    (0.62 is "varies by 62 %")
     * @param gapRatio    the largest gap over the expected spacing
     * @param supplyRatio vehicles running over vehicles the timetable calls for
     * @param basis       SCHEDULE when the expected spacing came from the timetable, OBSERVED when it is
     *                    the vehicles' own average
     */
    public record MetricsView(
            int vehicles,
            int gaps,
            Double meanGapMetres,
            Double maxGapMetres,
            Double variation,
            Double gapRatio,
            Double expectedGapMetres,
            Double expectedVehicles,
            Double supplyRatio,
            Double scheduledHeadwaySeconds,
            Double speedMetresPerSecond,
            int staleVehicles,
            int terminalVehicles,
            SpacingBasis basis) {

        static MetricsView of(HealthMetrics metrics) {
            return new MetricsView(
                    metrics.vehicles(),
                    metrics.gaps(),
                    round(metrics.meanGapMetres(), 10),
                    round(metrics.maxGapMetres(), 10),
                    round(metrics.variation(), 100),
                    round(metrics.gapRatio(), 100),
                    round(metrics.expectedGapMetres(), 10),
                    round(metrics.expectedVehicles(), 10),
                    round(metrics.supplyRatio(), 100),
                    round(metrics.scheduledHeadwaySeconds(), 1),
                    round(metrics.speedMetresPerSecond(), 100),
                    metrics.staleVehicles(),
                    metrics.terminalVehicles(),
                    metrics.basis());
        }

        private static Double round(Double value, double scale) {
            return value == null ? null : Math.round(value * scale) / scale;
        }
    }

    /**
     * A route in the all-routes list: enough to name it and show its verdict.
     *
     * @param vehicles vehicles the verdict was measured between, summed over directions
     */
    public record RouteHealthSummary(
            String routeId,
            String shortName,
            String longName,
            TransitMode mode,
            String colour,
            String textColour,
            RouteHealthState state,
            HealthConfidence confidence,
            List<ReasonCode> reasons,
            int vehicles) {
    }

    /** The current verdicts for every route that has vehicles. */
    public record RouteHealthList(List<RouteHealthSummary> routes, Instant computedAt) {
    }
}
