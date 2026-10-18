package ca.trackline.api.analytics.bunching;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.bunching.BunchingModels.DirectionSpacing;
import ca.trackline.api.analytics.bunching.BunchingModels.PlacedVehicle;
import ca.trackline.api.analytics.bunching.BunchingModels.VehicleRole;
import ca.trackline.api.analytics.spacing.SpacingBasis;

/**
 * Shapes returned by the bunching endpoint.
 *
 * <p>Two things travel together: where the vehicles are right now, which the spacing strip
 * draws, and the events recorded for the route, which say how long each bunch has lasted.
 * Distances are in metres rounded to a tenth.
 */
public final class BunchingDtos {

    private BunchingDtos() {
    }

    /**
     * @param directions the direction-by-direction positions behind the verdict; absent for a
     *                   route with no vehicles right now
     * @param events     bunches open now and those that resolved in the last day, newest first.
     *                   Times are when Trackline saw them, so each bounds the moment it describes
     * @param computedAt when the positions were analysed, or null when none have been
     */
    public record RouteBunchingView(
            String routeId, List<DirectionView> directions, List<BunchingEventView> events, Instant computedAt) {
    }

    /**
     * @param expectedGapMetres what spacing was measured against, or null with too few vehicles
     * @param vehicles          every placed vehicle in order along the route
     * @param bunches           the packs found now
     */
    public record DirectionView(
            short directionId,
            double lengthMetres,
            boolean loop,
            Double expectedGapMetres,
            SpacingBasis basis,
            List<VehicleView> vehicles,
            List<BunchView> bunches) {

        static DirectionView of(DirectionSpacing direction) {
            return new DirectionView(
                    direction.directionId(),
                    round(direction.lengthMetres()),
                    direction.loop(),
                    direction.expectedGapMetres() == null ? null : round(direction.expectedGapMetres()),
                    direction.basis(),
                    direction.vehicles().stream().map(VehicleView::of).toList(),
                    direction.bunches().stream().map(BunchView::of).toList());
        }
    }

    /**
     * @param role SPACED, BUNCHED, or why it is left out of the spacing: AT_TERMINAL or STALE
     */
    public record VehicleView(String vehicleId, double progressMetres, VehicleRole role) {

        static VehicleView of(PlacedVehicle vehicle) {
            return new VehicleView(vehicle.vehicleId(), round(vehicle.progressMetres()), vehicle.role());
        }
    }

    /**
     * @param spanMetres       from the first to the last vehicle of the pack
     * @param serviceGapMetres the largest gap elsewhere on the direction
     */
    public record BunchView(List<String> vehicleIds, double spanMetres, double serviceGapMetres, BunchingSeverity severity) {

        static BunchView of(BunchingCandidate bunch) {
            return new BunchView(bunch.vehicleIds(), round(bunch.spanMetres()), round(bunch.serviceGapMetres()), bunch.severity());
        }
    }

    public enum EventStatus {
        ACTIVE,
        RESOLVED
    }

    /**
     * One bunch over its life.
     *
     * @param vehicleIds    the vehicles at the latest sighting; a bunch that picks up or loses a
     *                      vehicle is still the same event
     * @param peakVehicles  the most vehicles it held
     * @param severity      at the latest sighting; {@code peakSeverity} is the worst it reached
     * @param sightings     how many analyses saw it
     * @param firstSeenAt   the first analysis that saw it: the pack formed at or before this
     * @param lastSeenAt    the latest that did
     * @param resolvedAt    the analysis that found it had been gone for two in a row, or null while
     *                      it lasts: it broke up at or before this, and not before {@code lastSeenAt}
     */
    public record BunchingEventView(
            long id,
            short directionId,
            EventStatus status,
            List<String> vehicleIds,
            int peakVehicles,
            double spanMetres,
            double expectedGapMetres,
            double serviceGapMetres,
            SpacingBasis basis,
            BunchingSeverity severity,
            BunchingSeverity peakSeverity,
            int sightings,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant resolvedAt) {
    }

    static double round(double metres) {
        return Math.round(metres * 10) / 10.0;
    }
}
