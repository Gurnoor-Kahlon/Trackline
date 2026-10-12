package ca.trackline.api.gtfs.realtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the real TTC feeds still match what Trackline was built on.
 *
 * <p>Excluded from the normal build and run deliberately — a test that reaches a
 * third party is not a unit test, and a CI failure caused by someone else's outage
 * teaches nothing. It exists so feed drift is discovered on purpose: run it when a
 * parser change is in question, or on a schedule that is allowed to be noisy.
 *
 * <pre>
 * ./mvnw test -Dgroups=live -DexcludedGroups= -DfailIfNoTests=false
 * </pre>
 *
 * <p>The assertions deliberately include things the feed does <em>not</em> carry.
 * Direction, delay and alert periods being absent is what forces Trackline to
 * infer direction from geometry, judge health by headway regularity, and observe
 * alert lifetimes itself. If any of those appear, those decisions can be revisited
 * — but only if something notices.
 */
@Tag("live")
class LiveFeedContractTest {

    private static final String BASE_URL = "https://bustime.ttc.ca/gtfsrt";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final RealtimeFeedParser parser = new RealtimeFeedParser();

    @Test
    void theVehicleFeedStillLooksLikeWhatWasMeasured() throws Exception {
        byte[] payload = fetch("vehicles");
        FeedSnapshot snapshot =
                parser.parse(RealtimeFeed.VEHICLE_POSITIONS, payload, new FeedEntityValidator(Set.of()), Instant.now());

        assertThat(snapshot.vehicles()).hasSizeGreaterThan(100);
        assertThat(snapshot.headerTimestamp()).isNotNull();

        long vehicles = snapshot.vehicles().size();
        long withRoute = snapshot.vehicles().stream().filter(VehicleSnapshot::hasRouteAssignment).count();
        long withOccupancy = snapshot.vehicles().stream().filter(v -> v.occupancyStatus() != null).count();

        // Position and occupancy are structural: measured at 100% and 97-99% at
        // every hour sampled.
        assertThat(snapshot.vehicles())
                .allSatisfy(v -> assertThat(FeedEntityValidator.withinServiceArea(v.latitude(), v.longitude()))
                        .isTrue());
        assertThat((double) withOccupancy / vehicles).isGreaterThan(0.9);

        // Route assignment is deliberately only checked for being non-zero. It
        // varies enormously with the hour: 77% of 1,363 vehicles at midday against
        // 28.8% of 649 at 03:38, because most of the overnight fleet is parked or
        // deadheading. A tighter band here would fail every night for no reason.
        assertThat(withRoute).isPositive();

        FeedMessage raw = FeedMessage.parseFrom(payload);
        boolean anyDirection = raw.getEntityList().stream()
                .filter(entity -> entity.hasVehicle())
                .anyMatch(entity -> entity.getVehicle().getTrip().hasDirectionId());

        // Still absent. Direction is inferred from route geometry because of this.
        assertThat(anyDirection).isFalse();
    }

    @Test
    void theTripUpdateFeedStillCarriesNoDelay() throws Exception {
        byte[] payload = fetch("trips");
        FeedMessage raw = FeedMessage.parseFrom(payload);

        boolean anyDelay = raw.getEntityList().stream()
                .filter(entity -> entity.hasTripUpdate())
                .anyMatch(entity -> {
                    var update = entity.getTripUpdate();
                    return update.hasDelay()
                            || update.getStopTimeUpdateList().stream()
                                    .anyMatch(call -> (call.hasArrival() && call.getArrival().hasDelay())
                                            || (call.hasDeparture() && call.getDeparture().hasDelay()));
                });

        assertThat(raw.getEntityCount()).isPositive();
        // Route health is headway-based because of this absence.
        assertThat(anyDelay).isFalse();
    }

    // active_period is marked deprecated in the GTFS-Realtime schema itself, not
    // merely in the generated bindings. Checking it is still worthwhile: the point
    // is that the TTC publishes none, and the spec deprecating the field is further
    // reason for Trackline to observe alert lifetimes rather than read them.
    @SuppressWarnings("deprecation")
    @Test
    void theAlertFeedStillCarriesNoPeriodOrSeverity() throws Exception {
        byte[] payload = fetch("alerts");
        FeedMessage raw = FeedMessage.parseFrom(payload);

        boolean anyPeriodOrSeverity = raw.getEntityList().stream()
                .filter(entity -> entity.hasAlert())
                .anyMatch(entity -> entity.getAlert().getActivePeriodCount() > 0
                        || entity.getAlert().hasSeverityLevel());

        // Trackline records when an alert appears and disappears because of this.
        assertThat(anyPeriodOrSeverity).isFalse();
    }

    @Test
    void everyAlertStillStatesNeitherACauseNorAnEffect() throws Exception {
        FeedSnapshot snapshot = parser.parse(
                RealtimeFeed.ALERTS, fetch("alerts"), new FeedEntityValidator(Set.of()), Instant.now());

        // Present but meaningless: UNKNOWN_CAUSE (1) and UNKNOWN_EFFECT (8) on every alert,
        // measured on 2026-10-12. Nothing is grouped or ranked by either because of this. If
        // the TTC starts stating them, grouping and health inputs can be revisited.
        assertThat(snapshot.alerts()).isNotEmpty();
        assertThat(snapshot.alerts()).allSatisfy(alert -> {
            assertThat(alert.cause()).isEqualTo(1);
            assertThat(alert.effect()).isEqualTo(8);
        });
    }

    @Test
    void alertHeadersAreStillCutOffWhileDescriptionsAreWhole() throws Exception {
        FeedSnapshot snapshot = parser.parse(
                RealtimeFeed.ALERTS, fetch("alerts"), new FeedEntityValidator(Set.of()), Instant.now());

        // The reason the interface shows the description and not the header.
        long cutOff = snapshot.alerts().stream()
                .filter(alert -> alert.description() != null && alert.header() != null)
                .filter(alert -> alert.description().length() > alert.header().length()
                        && alert.description().startsWith(alert.header()))
                .count();
        assertThat(cutOff).isPositive();
        assertThat(snapshot.alerts()).allSatisfy(alert -> assertThat(alert.header().length()).isLessThanOrEqualTo(40));
    }

    @Test
    void realtimeRouteIdsStillLookLikeScheduleRouteIds() throws Exception {
        FeedSnapshot snapshot = parser.parse(
                RealtimeFeed.VEHICLE_POSITIONS, fetch("vehicles"), new FeedEntityValidator(Set.of()), Instant.now());

        // route_id is the one identifier that joins realtime to the schedule — a
        // measured 100% against 2 of 1,050 for trip_id. The whole model keys on it.
        assertThat(snapshot.vehicles())
                .filteredOn(VehicleSnapshot::hasRouteAssignment)
                .allSatisfy(vehicle -> assertThat(vehicle.routeId()).matches("[0-9A-Za-z_-]+"));
    }

    private byte[] fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/" + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
