package ca.trackline.api.analytics.health;

import java.time.Instant;
import java.util.List;

/**
 * The value types of the route-health algorithm: what goes into the calculator and what
 * comes out of it. Plain records with no behaviour, shared by the pure calculator, the
 * service that feeds it and the API that serves its output.
 */
public final class RouteHealthModels {

    private RouteHealthModels() {
    }

    /**
     * Everything the calculator needs to judge one direction of one route.
     *
     * @param gapsMetres         distances along the route between consecutive usable
     *                           vehicles, in order; for a loop the gap that wraps past
     *                           the end is included
     * @param analysedVehicles   vehicles those gaps were measured between
     * @param freshPlacedVehicles every fresh vehicle placed in this direction, including
     *                           those at a terminal, which is the count that stands for
     *                           "vehicles running" against the timetable
     * @param staleVehicles      placed vehicles whose own report was too old to use
     * @param terminalVehicles   placed fresh vehicles left out of the gaps for being at a terminal
     * @param shapeLengthMetres  length of the direction's primary shape
     * @param loop               whether the route is a loop, so spacing wraps
     * @param scheduleCovered    whether the imported timetable describes today
     * @param scheduledHeadwaySeconds the timetable's median spacing for this hour, or null
     * @param speedMetresPerSecond the route's measured speed along its shape, or null
     */
    public record DirectionInputs(
            String routeId,
            short directionId,
            List<Double> gapsMetres,
            int analysedVehicles,
            int freshPlacedVehicles,
            int staleVehicles,
            int terminalVehicles,
            double shapeLengthMetres,
            boolean loop,
            boolean scheduleCovered,
            Double scheduledHeadwaySeconds,
            Double speedMetresPerSecond) {

        public DirectionInputs {
            gapsMetres = List.copyOf(gapsMetres);
        }
    }

    /** What the expected spacing was taken from. */
    public enum SpacingBasis {

        /** The timetable's headway, turned into a distance with the route's measured speed. */
        SCHEDULE,

        /** No timetable comparison was possible, so spacing is judged against its own average. */
        OBSERVED
    }

    /**
     * The figures behind a verdict, returned with it so the explanation can be checked.
     *
     * <p>Any figure that could not be computed is null, never zero.
     *
     * @param vehicles           vehicles the gaps were measured between
     * @param gaps               how many gaps that gave
     * @param meanGapMetres      their mean
     * @param maxGapMetres       the largest
     * @param variation          coefficient of variation of the gaps: their standard
     *                           deviation over their mean; null with fewer than three vehicles
     * @param gapRatio           the largest gap over the expected spacing
     * @param expectedGapMetres  the spacing the timetable implies, or the observed mean
     *                           when there was no timetable to compare against
     * @param expectedVehicles   how many vehicles that spacing implies along the route
     * @param supplyRatio        vehicles running over vehicles expected, with a timetable only
     * @param scheduledHeadwaySeconds the timetable's spacing in time
     * @param speedMetresPerSecond the measured speed used to turn it into a distance
     * @param staleVehicles      vehicles excluded for not having reported recently
     * @param terminalVehicles   vehicles excluded for being at a terminal
     * @param basis              what the expected spacing was taken from
     */
    public record HealthMetrics(
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
    }

    public record DirectionHealth(
            short directionId,
            RouteHealthState state,
            HealthConfidence confidence,
            List<ReasonCode> reasons,
            HealthMetrics metrics) {

        public DirectionHealth {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * A route's health: the worse of its directions, with each direction's own verdict.
     *
     * @param unplacedVehicles vehicles reporting on this route that could not be placed in
     *                         a direction, so are part of no spacing
     */
    public record RouteHealth(
            String routeId,
            RouteHealthState state,
            HealthConfidence confidence,
            List<ReasonCode> reasons,
            List<DirectionHealth> directions,
            int unplacedVehicles) {

        public RouteHealth {
            reasons = List.copyOf(reasons);
            directions = List.copyOf(directions);
        }
    }

    /**
     * Every route's health from one analysed snapshot.
     *
     * @param feedTimestamp the vehicle feed's own timestamp for the data analysed
     * @param computedAt    when the analysis ran
     * @param scheduleCovered whether the timetable described the day analysed
     * @param routes        a route appears only if at least one of its vehicles was seen
     */
    public record HealthSnapshot(
            Instant feedTimestamp, Instant computedAt, boolean scheduleCovered, List<RouteHealth> routes) {

        public HealthSnapshot {
            routes = List.copyOf(routes);
        }
    }
}
