package ca.trackline.api.analytics.bunching;

import java.util.List;

import ca.trackline.api.analytics.spacing.SpacingBasis;

/**
 * The value types of bunching detection.
 */
public final class BunchingModels {

    private BunchingModels() {
    }

    public enum BunchingSeverity {

        /** A bunch, with a gap elsewhere on the route between 1.8 and 2.5 times the expected spacing. */
        MODERATE,

        /** Three or more vehicles in one bunch, or a gap elsewhere above 2.5 times the expected spacing. */
        SEVERE;

        public static BunchingSeverity worse(BunchingSeverity first, BunchingSeverity second) {
            return first.ordinal() >= second.ordinal() ? first : second;
        }
    }

    /** A vehicle placed along a route, which is all the detector needs to know about it. */
    public record SpacedVehicle(String vehicleId, double progressMetres) {
    }

    /** What a placed vehicle is doing as far as spacing is concerned. */
    public enum VehicleRole {

        /** Part of the spacing and not in a bunch. */
        SPACED,

        /** Part of a bunch. */
        BUNCHED,

        /** Parked at a terminal, so left out of the spacing. */
        AT_TERMINAL,

        /** Has not reported recently, so left out of the spacing. */
        STALE
    }

    public record PlacedVehicle(String vehicleId, double progressMetres, VehicleRole role) {
    }

    /**
     * One direction as the spacing strip draws it.
     *
     * @param expectedGapMetres what spacing was measured against, or null when it could not be
     *                          worked out (fewer than two usable vehicles)
     * @param vehicles          every placed vehicle in order along the route, with its role
     * @param bunches           the packs found
     */
    public record DirectionSpacing(
            short directionId,
            double lengthMetres,
            boolean loop,
            Double expectedGapMetres,
            SpacingBasis basis,
            List<PlacedVehicle> vehicles,
            List<BunchingCandidate> bunches) {

        public DirectionSpacing {
            vehicles = List.copyOf(vehicles);
            bunches = List.copyOf(bunches);
        }
    }

    public record RouteSpacing(String routeId, List<DirectionSpacing> directions) {

        public RouteSpacing {
            directions = List.copyOf(directions);
        }
    }

    /**
     * Every route's spacing from one analysed cycle: the positions behind the verdicts.
     *
     * @param feedTimestamp the vehicle feed's own timestamp for the data analysed
     * @param computedAt    when the analysis ran
     */
    public record SpacingSnapshot(java.time.Instant feedTimestamp, java.time.Instant computedAt, List<RouteSpacing> routes) {

        public SpacingSnapshot {
            routes = List.copyOf(routes);
        }

        /** Every bunch in the snapshot. */
        public List<BunchingCandidate> bunches() {
            return routes.stream()
                    .flatMap(route -> route.directions().stream())
                    .flatMap(direction -> direction.bunches().stream())
                    .toList();
        }
    }

    /**
     * Vehicles running close together while a large gap opens elsewhere on the same direction.
     *
     * @param vehicleIds       the bunched vehicles, sorted by id so the same bunch always reads the same
     * @param spanMetres       distance from the first to the last vehicle of the bunch
     * @param expectedGapMetres what spacing was measured against
     * @param serviceGapMetres the largest gap elsewhere on the direction, which riders waiting
     *                         in it are subject to
     * @param basis            whether the expected spacing came from the timetable or from the
     *                         vehicles' own average
     */
    public record BunchingCandidate(
            String routeId,
            short directionId,
            List<String> vehicleIds,
            double spanMetres,
            double expectedGapMetres,
            double serviceGapMetres,
            BunchingSeverity severity,
            SpacingBasis basis) {

        public BunchingCandidate {
            vehicleIds = List.copyOf(vehicleIds);
        }

        public int vehicles() {
            return vehicleIds.size();
        }
    }
}
