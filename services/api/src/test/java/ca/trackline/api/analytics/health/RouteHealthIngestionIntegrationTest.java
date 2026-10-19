package ca.trackline.api.analytics.health;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.health.RouteHealthStore.HealthRead;
import ca.trackline.api.analytics.progress.ProjectionStatus;
import ca.trackline.api.analytics.progress.RouteProgress;
import ca.trackline.api.analytics.progress.RouteProgressSnapshot;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.ingestion.IngestionCycleResult;
import ca.trackline.api.ingestion.IngestionCycleService;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static ca.trackline.api.testsupport.FeedFixtures.bytes;
import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertFeedVersion;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertRoute;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertShape;
import static ca.trackline.api.testsupport.ProjectionFixtures.line;
import static ca.trackline.api.testsupport.ProjectionFixtures.loadRealShapes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Route health inside the real cycle and against real data.
 *
 * <p>Two things the pure tests cannot show: that the verdicts are built from the vehicles
 * the cycle actually placed, checked against the stored placements by an independent
 * calculation, and that the schedule-side queries (coverage, day type, hour) select what
 * they should.
 */
class RouteHealthIngestionIntegrationTest extends PostgisIntegrationTest {

    private static WireMockServer feedServer;

    @Autowired
    private IngestionCycleService cycleService;

    @Autowired
    private RouteHealthStore store;

    @Autowired
    private ca.trackline.api.analytics.bunching.BunchingEventService bunchingEvents;

    @Autowired
    private ca.trackline.api.analytics.history.HealthSampler sampler;

    @Autowired
    private ca.trackline.api.analytics.history.HealthRollup rollup;

    @Autowired
    private ca.trackline.api.persistence.query.ReliabilityQueryRepository reliabilityRows;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    /** A spy, so most tests run the real analysis and one can make it fail. */
    @MockitoSpyBean
    private RouteHealthService healthService;

    /** Fixed to when the fixtures were recorded; see the ingestion integration tests. */
    @MockitoBean
    private Clock clock;

    /**
     * Each cycle needs a feed timestamp the service has not seen, or it records "no change". The whole
     * recorded fleet is shifted by this many seconds, vehicle timestamps and clock included, so ages
     * and every validity window stay as they were recorded. Static so tests never reuse one.
     */
    private static long shiftSeconds = 1_000_000;

    @BeforeAll
    static void startFeedServer() {
        feedServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        feedServer.start();
    }

    @AfterAll
    static void stopFeedServer() {
        feedServer.stop();
    }

    @DynamicPropertySource
    static void pointAtTheFakeFeed(DynamicPropertyRegistry registry) {
        registry.add("trackline.gtfs.realtime.base-url", () -> feedServer.baseUrl() + "/gtfsrt");
    }

    @BeforeEach
    void setUp() {
        Mockito.when(clock.instant()).thenReturn(recordedAt());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        // Schedule coverage asks the clock for a Toronto view of it, which a bare mock answers with null.
        Mockito.when(clock.withZone(Mockito.any()))
                .thenAnswer(invocation -> Clock.fixed(clock.instant(), invocation.getArgument(0)));

        feedServer.resetAll();
        clear(jdbc);
        jdbc.execute("TRUNCATE service_alerts CASCADE");
        jdbc.execute("TRUNCATE bunching_events");
        jdbc.execute("TRUNCATE route_health_samples, route_headway_hourly");
        // Packs waiting to be confirmed are held in memory across tests, so one test's would count towards the next.
        bunchingEvents.forgetPending();
        sampler.forget();
        redis.delete(RouteHealthStore.KEY);
        redis.delete(RouteHealthStore.SPACING_KEY);
        store.forget();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(healthService);
        clear(jdbc);
        jdbc.execute("TRUNCATE bunching_events");
        jdbc.execute("TRUNCATE route_health_samples, route_headway_hourly");
        redis.delete(RouteHealthStore.KEY);
        redis.delete(RouteHealthStore.SPACING_KEY);
        store.forget();
    }

    private static Instant recordedAt() {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(bytes("vehicles.pb"));
            return Instant.ofEpochSecond(message.getHeader().getTimestamp());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /** The recorded fleet, moved on in time as a whole so each use is a new publication. */
    private byte[] freshVehicles() {
        long shift = shiftSeconds;
        // Far enough apart that the writer's de-duplication, which skips a vehicle that has neither
        // moved nor been silent for a minute, sees each use as news.
        shiftSeconds += 1_000;
        Mockito.when(clock.instant()).thenReturn(recordedAt().plusSeconds(shift));
        try {
            var builder = com.google.transit.realtime.GtfsRealtime.FeedMessage
                    .parseFrom(bytes("vehicles.pb"))
                    .toBuilder();
            builder.setHeader(builder.getHeader().toBuilder().setTimestamp(builder.getHeader().getTimestamp() + shift));
            for (var entity : builder.getEntityBuilderList()) {
                if (entity.hasVehicle() && entity.getVehicle().hasTimestamp()) {
                    entity.getVehicleBuilder().setTimestamp(entity.getVehicle().getTimestamp() + shift);
                }
            }
            return builder.build().toByteArray();
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private static void serve(byte[] body) {
        feedServer.stubFor(WireMock.get("/gtfsrt/vehicles")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/x-google-protobuf")
                        .withBody(body)));
    }

    private HealthSnapshot published() {
        HealthRead read = store.read();
        assertThat(read.snapshot()).as("a snapshot was published").isNotNull();
        return read.snapshot();
    }

    // --- in the cycle -----------------------------------------------------------------------------------

    @Test
    void aCycleOverTheRecordedFleetPublishesVerdictsToRedisAndIsRecordedAsSuccessful() {
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
        serve(freshVehicles());

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(redis.opsForValue().get(RouteHealthStore.KEY)).isNotBlank();
        HealthRead read = store.read();
        assertThat(read.source()).isEqualTo(DataSource.LIVE);
        assertThat(read.snapshot().routes()).isNotEmpty();
        assertThat(read.snapshot().feedTimestamp()).isNotNull();
    }

    @Test
    void everyVerdictIsConsistentWithItsDirections() {
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
        serve(freshVehicles());
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        for (RouteHealth route : published().routes()) {
            if (route.directions().isEmpty()) {
                assertThat(route.state()).isEqualTo(RouteHealthState.UNKNOWN);
                continue;
            }
            RouteHealthState worst = route.directions().stream()
                    .map(DirectionHealth::state)
                    .reduce(RouteHealthState.UNKNOWN, RouteHealthState::worse);
            assertThat(route.state()).as("route %s", route.routeId()).isEqualTo(worst);
            for (DirectionHealth direction : route.directions()) {
                if (direction.state() == RouteHealthState.UNKNOWN) {
                    assertThat(direction.confidence()).isEqualTo(HealthConfidence.NONE);
                } else {
                    assertThat(direction.confidence()).isNotEqualTo(HealthConfidence.NONE);
                    assertThat(direction.metrics().vehicles()).isGreaterThanOrEqualTo(2);
                }
            }
        }
    }

    @Test
    void theFiguresMatchAnIndependentCalculationFromTheStoredPlacements() {
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
        serve(freshVehicles());
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        HealthSnapshot snapshot = published();

        Map<String, Double> lengths = new HashMap<>();
        jdbc.query("SELECT route_id, direction_id, length_m FROM route_shapes WHERE is_primary", rows -> {
            lengths.put(rows.getString("route_id") + "/" + rows.getShort("direction_id"), rows.getDouble("length_m"));
        });

        int checked = 0;
        for (RouteHealth route : snapshot.routes()) {
            for (DirectionHealth direction : route.directions()) {
                double length = lengths.get(route.routeId() + "/" + direction.directionId());
                // The placements the cycle stored, read back and judged by the same rules done by hand:
                // reported within two minutes of the feed, and not within 200 m of either end.
                List<Double> usable = jdbc.query(
                        """
                        SELECT progress_m FROM vehicle_observations
                        WHERE route_id = ? AND inferred_direction_id = ? AND projection_status LIKE 'ASSIGNED%'
                          AND source_timestamp >= ?
                        ORDER BY progress_m
                        """,
                        (rows, index) -> rows.getDouble("progress_m"),
                        route.routeId(), direction.directionId(),
                        Timestamp.from(snapshot.feedTimestamp().minusSeconds(120)))
                        .stream()
                        .filter(progress -> progress >= 200 && progress <= length - 200)
                        .toList();

                assertThat(direction.metrics().vehicles())
                        .as("route %s direction %d", route.routeId(), direction.directionId())
                        .isEqualTo(usable.size());
                if (usable.size() >= 2) {
                    double widest = 0;
                    for (int index = 1; index < usable.size(); index++) {
                        widest = Math.max(widest, usable.get(index) - usable.get(index - 1));
                    }
                    assertThat(direction.metrics().maxGapMetres()).isCloseTo(widest, within(0.01));
                    checked++;
                }
            }
        }
        // The recorded fleet is trimmed, so only a few directions have two or more usable vehicles; a check
        // that compared nothing would pass vacuously, so at least three must have been compared.
        assertThat(checked).isGreaterThanOrEqualTo(3);
    }

    @Test
    void withNoTimetableImportedNothingIsComparedWithOneAndSaysSo() {
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
        serve(freshVehicles());
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        HealthSnapshot snapshot = published();

        assertThat(snapshot.scheduleCovered()).isFalse();
        assertThat(snapshot.routes().stream().flatMap(route -> route.directions().stream())
                .filter(direction -> direction.state() != RouteHealthState.UNKNOWN))
                .allSatisfy(direction -> assertThat(direction.reasons()).contains(ReasonCode.NO_SCHEDULE_COVERAGE));
    }

    @Test
    void aFailingAnalysisCostsTheVerdictsAndNothingElse() {
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
        Mockito.doThrow(new IllegalStateException("analysis failed")).when(healthService)
                .analyse(Mockito.anyList(), Mockito.any(), Mockito.any());
        double failuresBefore = meters.counter("trackline.analytics.health.failures").count();
        serve(freshVehicles());

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // The cycle still succeeds and its readings are stored; only the verdicts are missing.
        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(result.stored()).isPositive();
        assertThat(store.read().snapshot()).isNull();
        assertThat(meters.counter("trackline.analytics.health.failures").count()).isEqualTo(failuresBefore + 1);
    }

    @Test
    void whenNoRouteHasAShapeNothingIsPlacedAndEveryVerdictIsUnknown() {
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
        jdbc.execute("DELETE FROM route_shapes");
        serve(freshVehicles());

        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // With no shapes every routed vehicle is NO_GEOMETRY: the routes are listed, none has a verdict.
        HealthSnapshot snapshot = published();
        assertThat(snapshot.routes()).isNotEmpty()
                .allSatisfy(route -> assertThat(route.state()).isEqualTo(RouteHealthState.UNKNOWN));
    }

    // --- the schedule side ---------------------------------------------------------------------------------

    /** A straight route of about 8 km, one primary shape per direction, with four vehicles placed on it. */
    private static final String ROUTE = "ew";

    private long straightRoute() {
        long feedVersion = insertFeedVersion(jdbc);
        insertRoute(jdbc, feedVersion, ROUTE, ROUTE, "East-west", 3);
        double lat = 43.65;
        insertShape(jdbc, feedVersion, "ew-0", ROUTE, 0, line(-79.40, lat, -79.30, lat), true, false);
        insertShape(jdbc, feedVersion, "ew-1", ROUTE, 1, line(-79.30, lat + 0.0001, -79.40, lat + 0.0001), true, false);
        return feedVersion;
    }

    private void coveredBy(long feedVersion, String from, String to) {
        jdbc.update(
                "UPDATE gtfs_feed_versions SET feed_start_date = ?, feed_end_date = ? WHERE id = ?",
                Date.valueOf(from), Date.valueOf(to), feedVersion);
    }

    private void scheduled(long feedVersion, String dayType, int hour, int headwaySeconds) {
        jdbc.update(
                """
                INSERT INTO route_scheduled_headways (
                    route_id, direction_id, day_type, hour_of_day, median_headway_seconds,
                    gap_count, departure_count, feed_version_id)
                VALUES (?, 0, ?, ?, ?, 5, 6, ?)
                """,
                ROUTE, dayType, hour, headwaySeconds, feedVersion);
    }

    private HealthSnapshot analyseFourVehicles(Instant now) {
        Mockito.when(clock.instant()).thenReturn(now);
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        Map<String, RouteProgress> placed = new HashMap<>();
        double[] progress = {1500, 3000, 4500, 6000};
        for (int index = 0; index < progress.length; index++) {
            String id = "v" + index;
            vehicles.add(new VehicleSnapshot(id, ROUTE, null, 43.65, -79.38, 90f, 5f, null, null, null, null, now));
            placed.put(id, new RouteProgress(
                    id, ProjectionStatus.ASSIGNED_BY_BEARING, (short) 0, progress[index], progress[index] / 8_000, 3.0, null));
        }
        return healthService.analyse(vehicles, new RouteProgressSnapshot(placed), now).health();
    }

    private static DirectionHealth east(HealthSnapshot snapshot) {
        return snapshot.routes().getFirst().directions().getFirst();
    }

    @Test
    void aTimetableThatCoversTodayAndHasThisHoursSpacingIsHandedToTheJudgement() {
        long feed = straightRoute();
        // 2026-10-14 is a Wednesday. 14:00Z is 10:00 in Toronto.
        coveredBy(feed, "2026-09-30", "2026-10-31");
        scheduled(feed, "WEEKDAY", 10, 600);

        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-14T14:00:00Z"));

        assertThat(snapshot.scheduleCovered()).isTrue();
        assertThat(east(snapshot).metrics().scheduledHeadwaySeconds()).isEqualTo(600.0);
        // Present, but no speed has been measured in a single call, so it cannot be a distance yet.
        assertThat(east(snapshot).reasons()).contains(ReasonCode.SPEED_UNKNOWN);
    }

    @Test
    void aTimetableThatDoesNotReachTodayIsNotUsedEvenIfItHasRows() {
        long feed = straightRoute();
        coveredBy(feed, "2026-11-01", "2026-11-30");
        scheduled(feed, "WEEKDAY", 10, 600);

        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-14T14:00:00Z"));

        assertThat(snapshot.scheduleCovered()).isFalse();
        assertThat(east(snapshot).metrics().scheduledHeadwaySeconds()).isNull();
        assertThat(east(snapshot).reasons()).contains(ReasonCode.NO_SCHEDULE_COVERAGE);
    }

    @Test
    void theTimetableForTheWrongHourIsNotUsed() {
        long feed = straightRoute();
        coveredBy(feed, "2026-09-30", "2026-10-31");
        scheduled(feed, "WEEKDAY", 7, 600);

        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-14T14:00:00Z"));

        assertThat(east(snapshot).metrics().scheduledHeadwaySeconds()).isNull();
        assertThat(east(snapshot).reasons()).contains(ReasonCode.NO_SCHEDULED_HEADWAY);
    }

    @Test
    void aSaturdayUsesTheSaturdayTimetableNotTheWeekdayOne() {
        long feed = straightRoute();
        coveredBy(feed, "2026-09-30", "2026-10-31");
        scheduled(feed, "WEEKDAY", 10, 600);
        scheduled(feed, "SATURDAY", 10, 900);

        // 2026-10-17 is a Saturday.
        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-17T14:00:00Z"));

        assertThat(east(snapshot).metrics().scheduledHeadwaySeconds()).isEqualTo(900.0);
    }

    @Test
    void theHourIsTorontosNotUtcs() {
        long feed = straightRoute();
        coveredBy(feed, "2026-09-30", "2026-10-31");
        scheduled(feed, "WEEKDAY", 10, 600);
        scheduled(feed, "WEEKDAY", 14, 1200);

        // 14:00 UTC is 10:00 in Toronto in October, so the 10 o'clock row, not the 14 o'clock one.
        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-14T14:00:00Z"));

        assertThat(east(snapshot).metrics().scheduledHeadwaySeconds()).isEqualTo(600.0);
    }

    @Test
    void theDayTypeIsTorontosToo() {
        long feed = straightRoute();
        coveredBy(feed, "2026-09-30", "2026-10-31");
        scheduled(feed, "WEEKDAY", 22, 600);
        scheduled(feed, "SATURDAY", 22, 900);

        // 02:30 UTC on Saturday the 17th is 22:30 on Friday the 16th in Toronto: a weekday evening.
        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-17T02:30:00Z"));

        assertThat(east(snapshot).metrics().scheduledHeadwaySeconds()).isEqualTo(600.0);
    }

    @Test
    void vehiclesInsideTheRoutesOwnShapeAreAllMeasuredNoneSetAsideAsTerminalOnes() {
        long feed = straightRoute();
        coveredBy(feed, "2026-09-30", "2026-10-31");
        scheduled(feed, "WEEKDAY", 10, 600);

        HealthSnapshot snapshot = analyseFourVehicles(Instant.parse("2026-10-14T14:00:00Z"));

        assertThat(east(snapshot).metrics().vehicles()).isEqualTo(4);
        assertThat(east(snapshot).metrics().maxGapMetres()).isEqualTo(1500.0);
    }

    // --- bunching, end to end ---------------------------------------------------------------------------------

    /** Metres per degree of longitude along the straight test route's parallel, close enough to place vehicles. */
    private static final double METRES_PER_DEGREE = 80_550.0;

    private record Placement(String id, String route, double progressMetres) {
    }

    /**
     * A complete vehicle feed with each vehicle placed this far along the straight east-west route,
     * heading east so that its direction is read from its heading. A placement with no route is a
     * vehicle running without an assignment.
     */
    private byte[] syntheticFleet(List<Placement> placements) {
        long shift = shiftSeconds;
        shiftSeconds += 1_000;
        Instant at = recordedAt().plusSeconds(shift);
        Mockito.when(clock.instant()).thenReturn(at);

        var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(com.google.transit.realtime.GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setIncrementality(com.google.transit.realtime.GtfsRealtime.FeedHeader.Incrementality.FULL_DATASET)
                        .setTimestamp(at.getEpochSecond()));
        for (Placement placement : placements) {
            var vehicle = com.google.transit.realtime.GtfsRealtime.VehiclePosition.newBuilder()
                    .setVehicle(com.google.transit.realtime.GtfsRealtime.VehicleDescriptor.newBuilder().setId(placement.id()))
                    .setPosition(com.google.transit.realtime.GtfsRealtime.Position.newBuilder()
                            .setLatitude(43.65f)
                            .setLongitude((float) (-79.40 + placement.progressMetres() / METRES_PER_DEGREE))
                            .setBearing(90f))
                    .setTimestamp(at.getEpochSecond() - 10);
            if (placement.route() != null) {
                vehicle.setTrip(com.google.transit.realtime.GtfsRealtime.TripDescriptor.newBuilder()
                        .setTripId("trip-" + placement.id())
                        .setRouteId(placement.route()));
            }
            message.addEntity(com.google.transit.realtime.GtfsRealtime.FeedEntity.newBuilder()
                    .setId(placement.id())
                    .setVehicle(vehicle));
        }
        return message.build().toByteArray();
    }

    /** Ten vehicles in all: the four under test and six running with no route, so a later cycle can shrink sharply. */
    private List<Placement> fleet(double... progress) {
        List<Placement> placements = new ArrayList<>();
        for (int index = 0; index < progress.length; index++) {
            placements.add(new Placement("ew-" + index, ROUTE, progress[index]));
        }
        for (int index = 0; index < 6; index++) {
            placements.add(new Placement("spare-" + index, null, 0));
        }
        return placements;
    }

    private static final double[] BUNCHED = {1000, 1300, 5200, 7200};
    private static final double[] EVEN = {1000, 3000, 5000, 7000};

    private int eventCount(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM bunching_events WHERE " + where, Integer.class);
    }

    private void cycle(double[] progress) {
        serve(syntheticFleet(fleet(progress)));
        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
    }

    @Test
    void aBunchOnTheRoadIsFoundPublishedAndRecordedAsOneOpenEventOnceItHasPersisted() {
        straightRoute();

        // Found and published at once, but recorded only after three analyses in a row.
        cycle(BUNCHED);
        assertThat(store.readSpacing().snapshot().bunches()).hasSize(1);
        assertThat(eventCount("true")).isZero();
        cycle(BUNCHED);
        cycle(BUNCHED);

        var strip = store.readSpacing().snapshot().routes().stream()
                .filter(route -> route.routeId().equals(ROUTE)).findFirst().orElseThrow().directions().getFirst();
        assertThat(strip.bunches()).singleElement()
                .satisfies(bunch -> assertThat(bunch.vehicleIds()).containsExactly("ew-0", "ew-1"));
        assertThat(eventCount("resolved_at IS NULL AND route_id = 'ew'")).isEqualTo(1);
        // And the verdict says so, in the figures and in the reasons.
        DirectionHealth health = east(published());
        assertThat(health.metrics().bunchingClusters()).isEqualTo(1);
        assertThat(health.reasons()).contains(ReasonCode.BUNCHING);
    }

    @Test
    void aBunchThatLastsThroughSeveralCyclesIsOneEventNotOnePerCycle() {
        straightRoute();

        cycle(BUNCHED);
        cycle(BUNCHED);
        cycle(BUNCHED);

        assertThat(eventCount("route_id = 'ew'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sightings FROM bunching_events", Integer.class)).isEqualTo(3);
    }

    @Test
    void aBunchThatBreaksUpIsResolvedAfterTwoCyclesWithoutIt() {
        straightRoute();
        cycle(BUNCHED);
        cycle(BUNCHED);
        cycle(BUNCHED);

        cycle(EVEN);
        assertThat(eventCount("resolved_at IS NULL")).as("one miss is not enough").isEqualTo(1);

        cycle(EVEN);
        assertThat(eventCount("resolved_at IS NULL")).isZero();
        assertThat(eventCount("resolved_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void evenlySpacedVehiclesNeverMakeAnEvent() {
        straightRoute();

        cycle(EVEN);
        cycle(EVEN);

        assertThat(eventCount("true")).isZero();
        assertThat(store.readSpacing().snapshot().bunches()).isEmpty();
    }

    @Test
    void aSuspectCycleLeavesStoredBunchesAloneInsteadOfResolvingThemOnAShrunkenFleet() {
        straightRoute();
        cycle(BUNCHED);
        cycle(BUNCHED);
        cycle(BUNCHED);

        // One vehicle where there were ten: under a fifth of the last count, so the cycle is recorded as
        // suspect. Applied, it would resolve the bunch on the strength of a feed that has probably broken.
        serve(syntheticFleet(List.of(new Placement("spare-0", null, 0))));
        IngestionCycleResult suspect = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(suspect.status()).isEqualTo(IngestionRunStatus.SUSPECT);
        assertThat(eventCount("resolved_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT missed_cycles FROM bunching_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sightings FROM bunching_events", Integer.class)).isEqualTo(3);
    }

    @Test
    void aPackSeenOnlyOnceOrTwiceIsNeverRecordedAsAnEvent() {
        straightRoute();

        cycle(BUNCHED);
        cycle(BUNCHED);
        cycle(EVEN);

        assertThat(eventCount("true")).isZero();
    }

    @Test
    void aFailureRecordingEventsCostsTheEventsAndNothingElse() {
        straightRoute();
        jdbc.execute("ALTER TABLE bunching_events RENAME TO bunching_events_away");
        try {
            serve(syntheticFleet(fleet(BUNCHED)));

            IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

            // The readings and the verdicts are already stored and published; only the events failed.
            assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
            assertThat(result.stored()).isPositive();
            assertThat(store.read().snapshot()).isNotNull();
        } finally {
            jdbc.execute("ALTER TABLE bunching_events_away RENAME TO bunching_events");
        }
    }

    // --- history ---------------------------------------------------------------------------------------------

    private List<java.util.Map<String, Object>> samples() {
        return jdbc.queryForList(
                "SELECT * FROM route_health_samples WHERE route_id = 'ew' ORDER BY sampled_at");
    }

    @Test
    void aSuccessfulCycleRecordsTheDirectionsVerdictAndTheFiguresBehindIt() {
        straightRoute();

        cycle(BUNCHED);

        var recorded = samples();
        assertThat(recorded).singleElement().satisfies(row -> {
            DirectionHealth live = east(published());
            assertThat(row.get("direction_id")).isEqualTo(0);
            // What was stored is what was published, from the same analysis.
            assertThat(row.get("state")).isEqualTo(live.state().name());
            assertThat(row.get("confidence")).isEqualTo(live.confidence().name());
            assertThat(row.get("vehicles")).isEqualTo(live.metrics().vehicles());
            assertThat(row.get("bunching_clusters")).isEqualTo(1);
            assertThat(row.get("reason_codes").toString()).contains("BUNCHING");
        });
    }

    @Test
    void everyCycleFarEnoughApartAddsASampleOfWhatTheRoadWasLike() {
        straightRoute();

        // The test advances the clock by more than the sample interval each cycle.
        cycle(BUNCHED);
        cycle(EVEN);
        cycle(EVEN);

        assertThat(samples()).extracting(row -> row.get("bunching_clusters"))
                .containsExactly(1, 0, 0);
    }

    @Test
    void aSuspectCycleIsNotRecordedBecauseItsVerdictsCameFromAFeedThatWasNotTrusted() {
        straightRoute();
        cycle(BUNCHED);

        serve(syntheticFleet(List.of(new Placement("spare-0", null, 0))));
        IngestionCycleResult suspect = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(suspect.status()).isEqualTo(IngestionRunStatus.SUSPECT);
        assertThat(samples()).hasSize(1);
    }

    @Test
    void aFailureRecordingASampleCostsTheSampleAndNothingElseAndIsTriedAgainAtOnce() {
        straightRoute();
        jdbc.execute("ALTER TABLE route_health_samples RENAME TO route_health_samples_away");
        try {
            serve(syntheticFleet(fleet(BUNCHED)));

            IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

            // The readings and the verdicts are already stored and published; only the history failed.
            assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
            assertThat(result.stored()).isPositive();
            assertThat(store.read().snapshot()).isNotNull();
        } finally {
            jdbc.execute("ALTER TABLE route_health_samples_away RENAME TO route_health_samples");
        }

        // Nothing was recorded, so the next cycle records, and does not wait out an interval it did not use.
        cycle(BUNCHED);
        assertThat(samples()).hasSize(1);
    }

    @Test
    void theSamplesBecomeReliabilityThroughTheHourlySummary() {
        straightRoute();
        cycle(BUNCHED);
        cycle(EVEN);
        cycle(EVEN);

        // Long after, so every hour the samples fall in has finished.
        int written = rollup.rollUp(Instant.parse("2027-06-01T00:00:00Z"));
        var rows = reliabilityRows.findForRoute(ROUTE, Instant.EPOCH);

        assertThat(written).isPositive();
        assertThat(rows.stream().mapToInt(row -> row.samples()).sum()).isEqualTo(3);
        assertThat(rows.stream().mapToInt(row -> row.bunchingSamples()).sum()).isEqualTo(1);

        // Three samples are not a reliability figure, and the shipped gate says so.
        var shipped = new ca.trackline.api.analytics.history.ReliabilityCalculator(
                new ca.trackline.api.analytics.history.HistoryProperties.Reliability(24, 20, 240, 60, 3, 2),
                ZoneOffset.UTC);
        assertThat(shipped.calculate(rows).reason())
                .isEqualTo(ca.trackline.api.analytics.history.ReliabilityModels.InsufficientReason.TOO_FEW_HOURS);

        // With the gate opened to one sample, the same rows give figures, so the chain from cycle to figure is whole.
        var open = new ca.trackline.api.analytics.history.ReliabilityCalculator(
                new ca.trackline.api.analytics.history.HistoryProperties.Reliability(1, 1, 1, 1, 1, 1),
                ZoneOffset.UTC);
        var result = open.calculate(rows);
        assertThat(result.status())
                .isEqualTo(ca.trackline.api.analytics.history.ReliabilityModels.ReliabilityStatus.AVAILABLE);
        assertThat(result.summary().samples()).isEqualTo(3);
        assertThat(result.summary().bunchingShare()).isCloseTo(1.0 / 3.0, within(1e-9));
    }
}
