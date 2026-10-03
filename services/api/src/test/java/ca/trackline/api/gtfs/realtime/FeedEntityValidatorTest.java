package ca.trackline.api.gtfs.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide whether a feed entity is usable.
 *
 * <p>Each rejects a value that would otherwise produce a visibly wrong result:
 * a vehicle in the ocean, a position attributed to a route that does not exist, a
 * reading that cannot be aged.
 */
class FeedEntityValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private final FeedEntityValidator validator = new FeedEntityValidator(Set.of("501", "41"));

    @Test
    void acceptsAPlausibleVehicle() {
        assertThat(validator.rejectVehicle(vehicle("1234", "501", 43.6532, -79.3832, NOW), NOW)).isEmpty();
    }

    @Test
    void acceptsAVehicleWithNoRouteAssignment() {
        // About a quarter of the fleet at any moment: deadheading or between runs.
        // They are real vehicles and dropping them would understate the fleet.
        assertThat(validator.rejectVehicle(vehicle("1234", null, 43.6532, -79.3832, NOW), NOW)).isEmpty();
    }

    @Test
    void rejectsAVehicleOnARouteTheScheduleDoesNotContain() {
        assertThat(validator.rejectVehicle(vehicle("1234", "999", 43.6532, -79.3832, NOW), NOW))
                .contains(RejectionReason.UNKNOWN_ROUTE);
    }

    @Test
    void acceptsAnyRouteBeforeAScheduleHasBeenImported() {
        // With no schedule loaded, rejecting every route would discard the entire
        // feed and make the system look broken rather than merely unconfigured.
        FeedEntityValidator noSchedule = new FeedEntityValidator(Set.of());

        assertThat(noSchedule.rejectVehicle(vehicle("1234", "999", 43.6532, -79.3832, NOW), NOW)).isEmpty();
    }

    @Test
    void rejectsCoordinatesOutsideTheServiceArea() {
        assertThat(validator.rejectVehicle(vehicle("1234", "501", 0, 0, NOW), NOW))
                .contains(RejectionReason.COORDINATES_OUT_OF_RANGE);
        assertThat(validator.rejectVehicle(vehicle("1234", "501", 51.5, -0.12, NOW), NOW))
                .contains(RejectionReason.COORDINATES_OUT_OF_RANGE);
    }

    @Test
    void acceptsTheEdgesOfTheServiceArea() {
        assertThat(FeedEntityValidator.withinServiceArea(
                        FeedEntityValidator.MIN_LATITUDE, FeedEntityValidator.MIN_LONGITUDE))
                .isTrue();
        assertThat(FeedEntityValidator.withinServiceArea(
                        FeedEntityValidator.MAX_LATITUDE, FeedEntityValidator.MAX_LONGITUDE))
                .isTrue();
    }

    @Test
    void rejectsAReadingWithoutAnIdentifierOrTimestamp() {
        assertThat(validator.rejectVehicle(vehicle(null, "501", 43.6, -79.4, NOW), NOW))
                .contains(RejectionReason.MISSING_VEHICLE_ID);
        assertThat(validator.rejectVehicle(vehicle("1234", "501", 43.6, -79.4, null), NOW))
                .contains(RejectionReason.MISSING_TIMESTAMP);
    }

    @Test
    void toleratesModestClockSkewButNotMinutesOfIt() {
        // The publisher's clock is not ours, so a reading a little ahead is normal.
        assertThat(validator.rejectVehicle(vehicle("1234", "501", 43.6, -79.4, NOW.plusSeconds(30)), NOW))
                .isEmpty();
        assertThat(validator.rejectVehicle(
                        vehicle("1234", "501", 43.6, -79.4, NOW.plus(FeedEntityValidator.MAX_CLOCK_SKEW)
                                .plusSeconds(1)), NOW))
                .contains(RejectionReason.TIMESTAMP_IN_FUTURE);
    }

    @Test
    void rejectsAReadingTooOldToBeAPosition() {
        assertThat(validator.rejectVehicle(
                        vehicle("1234", "501", 43.6, -79.4, NOW.minus(FeedEntityValidator.MAX_AGE).minusSeconds(1)),
                        NOW))
                .contains(RejectionReason.TIMESTAMP_TOO_OLD);

        // A parked vehicle reporting the same timestamp for a while is still usable.
        assertThat(validator.rejectVehicle(vehicle("1234", "501", 43.6, -79.4, NOW.minusSeconds(600)), NOW))
                .isEmpty();
    }

    @Test
    void rejectsATripUpdateWithNoPredictedCalls() {
        TripUpdateSnapshot empty = new TripUpdateSnapshot("t1", "501", "1234", List.of(), NOW);

        assertThat(validator.rejectTripUpdate(empty)).contains(RejectionReason.NO_STOP_TIME_UPDATES);
    }

    @Test
    void rejectsAnAlertThatNamesNothing() {
        AlertSnapshot unattributed =
                new AlertSnapshot("a1", 1, 8, "Something happened", null, null, List.of());

        assertThat(validator.rejectAlert(unattributed)).contains(RejectionReason.NO_INFORMED_ENTITY);
    }

    private static VehicleSnapshot vehicle(
            String vehicleId, String routeId, double latitude, double longitude, Instant timestamp) {
        return new VehicleSnapshot(
                vehicleId, routeId, null, latitude, longitude, null, null, null, null, null, null, timestamp);
    }
}
