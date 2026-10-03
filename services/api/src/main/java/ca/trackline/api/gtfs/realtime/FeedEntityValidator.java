package ca.trackline.api.gtfs.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a feed entity is usable.
 *
 * <p>The feed is a third party's output over the public internet, so nothing in it
 * is assumed. The checks are deliberately about plausibility rather than taste:
 * each one rejects a value that would produce a visibly wrong result — a vehicle
 * in the Atlantic, a position attributed to a route that does not exist, a reading
 * that cannot be aged because it has no timestamp.
 *
 * <p>Pure, so every rule can be tested without a feed or a database.
 */
public final class FeedEntityValidator {

    /**
     * Toronto and a generous margin. The TTC runs a little past the city boundary;
     * anything outside this is a corrupt coordinate, not a bus.
     */
    public static final double MIN_LATITUDE = 42.5;
    public static final double MAX_LATITUDE = 45.5;
    public static final double MIN_LONGITUDE = -81.5;
    public static final double MAX_LONGITUDE = -77.5;

    /**
     * A reading may be slightly ahead of our clock — the publisher's clock is not
     * ours — but not by minutes.
     */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    /**
     * Beyond this a reading is history rather than a position. Generous, because a
     * parked vehicle legitimately reports the same timestamp for a while, and the
     * partition it would be stored in must still exist.
     */
    public static final Duration MAX_AGE = Duration.ofHours(6);

    private final Set<String> knownRouteIds;

    /**
     * @param knownRouteIds routes in the active schedule; an empty set disables the
     *                      route check, which is what happens before any schedule
     *                      has been imported
     */
    public FeedEntityValidator(Set<String> knownRouteIds) {
        this.knownRouteIds = Set.copyOf(knownRouteIds);
    }

    /**
     * @return the reason to drop this vehicle, or empty if it is usable
     */
    public Optional<RejectionReason> rejectVehicle(VehicleSnapshot vehicle, Instant now) {
        if (vehicle.vehicleId() == null || vehicle.vehicleId().isBlank()) {
            return Optional.of(RejectionReason.MISSING_VEHICLE_ID);
        }
        if (vehicle.sourceTimestamp() == null) {
            return Optional.of(RejectionReason.MISSING_TIMESTAMP);
        }
        if (vehicle.sourceTimestamp().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            return Optional.of(RejectionReason.TIMESTAMP_IN_FUTURE);
        }
        if (vehicle.sourceTimestamp().isBefore(now.minus(MAX_AGE))) {
            return Optional.of(RejectionReason.TIMESTAMP_TOO_OLD);
        }
        if (!withinServiceArea(vehicle.latitude(), vehicle.longitude())) {
            return Optional.of(RejectionReason.COORDINATES_OUT_OF_RANGE);
        }
        // An unassigned vehicle has no route and is still a real vehicle, so only a
        // route the schedule does not contain is a problem.
        if (vehicle.routeId() != null && !knownRouteIds.isEmpty() && !knownRouteIds.contains(vehicle.routeId())) {
            return Optional.of(RejectionReason.UNKNOWN_ROUTE);
        }
        return Optional.empty();
    }

    public Optional<RejectionReason> rejectTripUpdate(TripUpdateSnapshot tripUpdate) {
        if (tripUpdate.calls().isEmpty()) {
            return Optional.of(RejectionReason.NO_STOP_TIME_UPDATES);
        }
        if (tripUpdate.routeId() != null
                && !knownRouteIds.isEmpty()
                && !knownRouteIds.contains(tripUpdate.routeId())) {
            return Optional.of(RejectionReason.UNKNOWN_ROUTE);
        }
        return Optional.empty();
    }

    public Optional<RejectionReason> rejectAlert(AlertSnapshot alert) {
        if (alert.affected().isEmpty()) {
            return Optional.of(RejectionReason.NO_INFORMED_ENTITY);
        }
        return Optional.empty();
    }

    public static boolean withinServiceArea(double latitude, double longitude) {
        return latitude >= MIN_LATITUDE
                && latitude <= MAX_LATITUDE
                && longitude >= MIN_LONGITUDE
                && longitude <= MAX_LONGITUDE;
    }
}
