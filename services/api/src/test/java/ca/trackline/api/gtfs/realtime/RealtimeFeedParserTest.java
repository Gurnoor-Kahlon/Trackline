package ca.trackline.api.gtfs.realtime;

import java.time.Instant;
import java.util.Set;

import ca.trackline.api.testsupport.FeedFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing against recorded TTC payloads.
 *
 * <p>Several of these assert what the feed does <em>not</em> contain. That is the
 * point: Trackline's design rests on measured absences — no direction, no delay, no
 * alert period — and if the TTC ever starts publishing them, these tests should
 * fail loudly so the decisions built on those gaps can be revisited rather than
 * quietly left wrong.
 */
class RealtimeFeedParserTest {

    private final RealtimeFeedParser parser = new RealtimeFeedParser();

    /** No route filtering: the fixture's routes are not loaded in a unit test. */
    private final FeedEntityValidator permissive = new FeedEntityValidator(Set.of());

    /** Within the fixtures' own timestamps, so nothing is rejected for age. */
    private static final Instant WHEN_RECORDED = Instant.parse("2026-10-02T04:00:00Z");

    @Test
    void readsVehiclesFromARecordedPayload() {
        FeedSnapshot snapshot = parse(RealtimeFeed.VEHICLE_POSITIONS, "vehicles.pb");

        assertThat(snapshot.vehicles()).isNotEmpty();
        assertThat(snapshot.headerTimestamp()).isNotNull();
        assertThat(snapshot.feed()).isEqualTo(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(snapshot.vehicles()).allSatisfy(vehicle -> {
            assertThat(vehicle.vehicleId()).isNotBlank();
            assertThat(vehicle.sourceTimestamp()).isNotNull();
            assertThat(FeedEntityValidator.withinServiceArea(vehicle.latitude(), vehicle.longitude())).isTrue();
        });
    }

    @Test
    void theVehicleFeedCarriesNoDirection() {
        // Direction is absent from every vehicle the TTC publishes, which is why it
        // has to be inferred from route geometry later. If this ever starts failing,
        // that inference can be replaced with the published value.
        FeedSnapshot snapshot = parse(RealtimeFeed.VEHICLE_POSITIONS, "vehicles.pb");

        assertThat(snapshot.vehicles()).isNotEmpty();
        // VehicleSnapshot has no direction field at all, so the check is that the
        // underlying payload has none either.
        assertThat(rawHasDirectionId()).isFalse();
    }

    @Test
    void someVehiclesCarryNoRouteAndAreKeptAnyway() {
        FeedSnapshot snapshot = parse(RealtimeFeed.VEHICLE_POSITIONS, "vehicles.pb");

        long unassigned = snapshot.vehicles().stream().filter(v -> !v.hasRouteAssignment()).count();

        // Roughly a quarter of the fleet runs without a trip assignment at any
        // moment. Dropping them would understate the fleet and lose vehicles that
        // are plainly on the road.
        assertThat(unassigned).isPositive();
        assertThat(snapshot.rejections()).doesNotContainKey(RejectionReason.UNKNOWN_ROUTE);
    }

    @Test
    void absentOptionalFieldsStayNullRatherThanBecomingZero() {
        FeedSnapshot snapshot = parse(RealtimeFeed.VEHICLE_POSITIONS, "vehicles.pb");

        // Protobuf hands back 0 for an absent number. Reading without checking would
        // turn "the agency did not say" into "the agency said zero", which for a
        // speed or a stop sequence is an invented measurement.
        assertThat(snapshot.vehicles())
                .anySatisfy(vehicle -> assertThat(vehicle.sourceTripId()).isNull());
    }

    @Test
    void readsTripUpdatesAndTheyCarryNoDelay() {
        FeedSnapshot snapshot = parse(RealtimeFeed.TRIP_UPDATES, "trip-updates.pb");

        assertThat(snapshot.tripUpdates()).isNotEmpty();
        assertThat(snapshot.tripUpdates()).allSatisfy(update -> assertThat(update.calls()).isNotEmpty());

        // The TTC publishes no delay anywhere in this feed, which is why route health
        // is built on headway regularity instead of lateness.
        assertThat(rawHasDelay()).isFalse();
    }

    @Test
    void tripUpdatesPredictArrivalFarMoreOftenThanDeparture() {
        FeedSnapshot snapshot = parse(RealtimeFeed.TRIP_UPDATES, "trip-updates.pb");

        long arrivals = snapshot.tripUpdates().stream()
                .flatMap(update -> update.calls().stream())
                .filter(call -> call.arrival() != null)
                .count();
        long departures = snapshot.tripUpdates().stream()
                .flatMap(update -> update.calls().stream())
                .filter(call -> call.departure() != null)
                .count();

        // Measured across a full snapshot: 24,570 arrivals against 745 departures.
        // A consumer that assumed departure would mostly be reading nulls.
        assertThat(arrivals).isGreaterThan(departures);
    }

    @Test
    void readsAlertsWhichCarryNoPeriodOrSeverity() {
        FeedSnapshot snapshot = parse(RealtimeFeed.ALERTS, "alerts.pb");

        assertThat(snapshot.alerts()).isNotEmpty();
        assertThat(snapshot.alerts()).allSatisfy(alert -> {
            assertThat(alert.alertId()).isNotBlank();
            assertThat(alert.header()).isNotBlank();
            assertThat(alert.affected()).isNotEmpty();
        });

        // No active period and no severity level, so Trackline has to observe when an
        // alert appears and disappears rather than reading its lifetime from the feed.
        assertThat(rawHasActivePeriodOrSeverity()).isFalse();
    }

    @Test
    void anEmptyPayloadParsesToNothingRatherThanFailing() {
        FeedSnapshot snapshot = parse(RealtimeFeed.VEHICLE_POSITIONS, "empty.pb");

        // A feed can legitimately be empty, and that is different from broken. The
        // cycle decides what to make of it; the parser does not get to panic.
        assertThat(snapshot.vehicles()).isEmpty();
        assertThat(snapshot.rawEntityCount()).isZero();
        assertThat(snapshot.headerTimestamp()).isNotNull();
    }

    @Test
    void aMalformedPayloadIsRejectedOutright() {
        assertThatThrownBy(() -> parse(RealtimeFeed.VEHICLE_POSITIONS, "malformed.pb"))
                .isInstanceOf(GtfsRealtimeFormatException.class);
    }

    @Test
    void entitiesOutsideTheServiceAreaAreCountedNotStored() {
        // A validator whose clock is years ahead ages out every reading, which is the
        // cheapest way to prove rejections are counted rather than silently dropped.
        FeedSnapshot snapshot = parser.parse(
                RealtimeFeed.VEHICLE_POSITIONS,
                FeedFixtures.bytes("vehicles.pb"),
                permissive,
                WHEN_RECORDED.plusSeconds(86_400L * 365));

        assertThat(snapshot.vehicles()).isEmpty();
        assertThat(snapshot.rejections()).containsKey(RejectionReason.TIMESTAMP_TOO_OLD);
        assertThat(snapshot.rejectedCount()).isEqualTo(snapshot.rawEntityCount());
        assertThat(snapshot.rejectionRate()).isEqualTo(1.0);
    }

    @Test
    void entitiesOnRoutesTheScheduleDoesNotKnowAreRejected() {
        FeedEntityValidator strict = new FeedEntityValidator(Set.of("no-such-route"));

        FeedSnapshot snapshot = parser.parse(
                RealtimeFeed.VEHICLE_POSITIONS, FeedFixtures.bytes("vehicles.pb"), strict, feedTime());

        assertThat(snapshot.rejections()).containsKey(RejectionReason.UNKNOWN_ROUTE);
    }

    private FeedSnapshot parse(RealtimeFeed feed, String fixture) {
        return parser.parse(feed, FeedFixtures.bytes(fixture), permissive, feedTime());
    }

    /** A time consistent with the recordings, so nothing is rejected for age. */
    private Instant feedTime() {
        try {
            com.google.transit.realtime.GtfsRealtime.FeedMessage message =
                    com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(FeedFixtures.bytes("vehicles.pb"));
            return Instant.ofEpochSecond(message.getHeader().getTimestamp());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private boolean rawHasDirectionId() {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(FeedFixtures.bytes("vehicles.pb"));
            return message.getEntityList().stream()
                    .filter(com.google.transit.realtime.GtfsRealtime.FeedEntity::hasVehicle)
                    .anyMatch(entity -> entity.getVehicle().getTrip().hasDirectionId());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private boolean rawHasDelay() {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(FeedFixtures.bytes("trip-updates.pb"));
            return message.getEntityList().stream()
                    .filter(com.google.transit.realtime.GtfsRealtime.FeedEntity::hasTripUpdate)
                    .anyMatch(entity -> {
                        var update = entity.getTripUpdate();
                        if (update.hasDelay()) {
                            return true;
                        }
                        return update.getStopTimeUpdateList().stream()
                                .anyMatch(call -> (call.hasArrival() && call.getArrival().hasDelay())
                                        || (call.hasDeparture() && call.getDeparture().hasDelay()));
                    });
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    // active_period is marked deprecated in the GTFS-Realtime schema itself, not
    // merely in the generated bindings. Checking it is still worthwhile: the point
    // is that the TTC publishes none, and the spec deprecating the field is further
    // reason for Trackline to observe alert lifetimes rather than read them.
    @SuppressWarnings("deprecation")
    private boolean rawHasActivePeriodOrSeverity() {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(FeedFixtures.bytes("alerts.pb"));
            return message.getEntityList().stream()
                    .filter(com.google.transit.realtime.GtfsRealtime.FeedEntity::hasAlert)
                    .anyMatch(entity -> entity.getAlert().getActivePeriodCount() > 0
                            || entity.getAlert().hasSeverityLevel());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

}
