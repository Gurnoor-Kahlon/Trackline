package ca.trackline.api.ingestion;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import ca.trackline.api.analytics.progress.ProjectionStatus;
import ca.trackline.api.analytics.progress.RouteProgress;
import ca.trackline.api.analytics.progress.RouteProgressProjector;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Projection inside the real cycle: stored with the reading, and unable to lose it.
 */
class ProgressIngestionIntegrationTest extends PostgisIntegrationTest {

    private static WireMockServer feedServer;

    @Autowired
    private IngestionCycleService cycleService;

    @Autowired
    private VehicleObservationWriter observationWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    /** A spy, so most tests run the real projector and one can make it fail. */
    @MockitoSpyBean
    private RouteProgressProjector projector;

    /** Fixed to when the fixtures were recorded; see {@link IngestionCycleIntegrationTest}. */
    @MockitoBean
    private Clock clock;

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
        Instant recordedAt = headerTimestampOf("vehicles.pb");
        Mockito.when(clock.instant()).thenReturn(recordedAt);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        feedServer.resetAll();
        observationWriter.forgetAll();
        cycleService.forgetFeedState();
        clear(jdbc);
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(projector);
        clear(jdbc);
    }

    // --- persisted beside the reading --------------------------------------------------

    @Test
    void aCycleStoresEachVehiclesPlacementBesideItsReading() {
        serve(bytes("vehicles.pb"));

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        Map<String, Integer> byStatus = countsByStatus();
        assertThat(byStatus.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(result.stored());

        // Seventeen vehicles had no route and each says so; none is left blank.
        assertThat(byStatus).containsEntry("NO_ROUTE", 17);
        assertThat(byStatus).doesNotContainKey(null);

        // Every vehicle the feed put on a route got an outcome other than "never projected".
        Integer unprojected = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_observations WHERE projection_status IS NULL", Integer.class);
        assertThat(unprojected).isZero();
    }

    @Test
    void anAssignedRowsProgressIsItsFractionOfTheShapeItWasMatchedTo() {
        serve(bytes("vehicles.pb"));
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // Re-derived from the shape the row names, independently of the projector.
        Integer inconsistent = jdbc.queryForObject(
                """
                SELECT count(*) FROM vehicle_observations o
                JOIN route_shapes s ON s.route_id = o.route_id
                                   AND s.direction_id = o.inferred_direction_id AND s.is_primary
                WHERE o.projection_status LIKE 'ASSIGNED%'
                  AND abs(o.progress_m - o.progress_fraction * s.length_m) > 0.01
                """,
                Integer.class);
        Integer assigned = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_observations WHERE projection_status LIKE 'ASSIGNED%'", Integer.class);

        assertThat(assigned).isPositive();
        assertThat(inconsistent).isZero();
    }

    @Test
    void offRouteAndUnknownVehiclesAreStoredWithTheirStatusAndNoPosition() {
        serve(bytes("vehicles.pb"));
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        Integer positioned = jdbc.queryForObject(
                """
                SELECT count(*) FROM vehicle_observations
                WHERE projection_status NOT LIKE 'ASSIGNED%'
                  AND (inferred_direction_id IS NOT NULL OR progress_m IS NOT NULL OR progress_fraction IS NOT NULL)
                """,
                Integer.class);
        assertThat(positioned).isZero();

        // The evidence for an off-route verdict is kept: how far away it was.
        List<Double> offsets = jdbc.queryForList(
                "SELECT projection_offset_m FROM vehicle_observations WHERE projection_status = 'OFF_ROUTE'",
                Double.class);
        assertThat(offsets).isNotEmpty().allSatisfy(offset -> assertThat(offset).isGreaterThan(150));
    }

    // --- movement, end to end through the database --------------------------------------

    @Test
    void anEarlierStoredReadingDecidesTheDirectionWhenTheHeadingIsMissing() {
        long feedVersion = jdbc.queryForObject("SELECT id FROM gtfs_feed_versions", Long.class);
        double lat = 43.65;
        insertRoute(jdbc, feedVersion, "ew", "ew", "East-west", 3);
        insertShape(jdbc, feedVersion, "ew-0", "ew", 0, line(-79.40, lat, -79.39, lat, -79.38, lat), true, false);
        insertShape(jdbc, feedVersion, "ew-1", "ew", 1,
                line(-79.38, lat + 0.0001, -79.39, lat + 0.0001, -79.40, lat + 0.0001), true, false);

        Instant now = headerTimestampOf("vehicles.pb");
        observationWriter.ensurePartitionFor(now);
        long run = jdbc.queryForObject(
                "INSERT INTO ingestion_runs (feed, started_at, duration_ms, status) "
                        + "VALUES ('VEHICLE_POSITIONS', now(), 0, 'SUCCESS') RETURNING id",
                Long.class);
        // 250 m behind, 40 s ago, on the same route: it has been travelling east.
        jdbc.update(
                """
                INSERT INTO vehicle_observations (vehicle_id, source_timestamp, observed_at, route_id, position, ingestion_run_id)
                VALUES ('walker', ?, ?, 'ew', ST_SetSRID(ST_MakePoint(-79.3975, ?), 4326)::geography, ?)
                """,
                Timestamp.from(now.minusSeconds(40)), Timestamp.from(now.minusSeconds(40)), lat, run);

        // No heading at all, so only movement can say which way it is going.
        VehicleSnapshot walker = new VehicleSnapshot(
                "walker", "ew", null, lat + 0.00005, -79.395, null, 8f, null, null, null, null, now);

        RouteProgress placed = projector.project(List.of(walker)).get("walker").orElseThrow();

        assertThat(placed.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_MOVEMENT);
        assertThat(placed.directionId()).isEqualTo((short) 0);
        assertThat(placed.progressFraction()).isCloseTo(0.25, within(0.01));
    }

    // --- projection must never cost the readings -------------------------------------------

    @Test
    void aFailingProjectionStillStoresTheReadingsAndRecordsThemAsUnprojected() {
        Mockito.doThrow(new IllegalStateException("shape query failed")).when(projector).project(Mockito.anyList());
        double failuresBefore = meters.counter("trackline.analytics.projection.failures").count();
        serve(bytes("vehicles.pb"));

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // The cycle itself succeeded: derived data going wrong is not a failed ingestion.
        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(result.stored()).isPositive();

        Integer total = jdbc.queryForObject("SELECT count(*) FROM vehicle_observations", Integer.class);
        Integer unprojected = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_observations WHERE projection_status IS NULL", Integer.class);
        assertThat(total).isEqualTo(result.stored());
        // Null means "never projected" and is distinct from every real outcome.
        assertThat(unprojected).isEqualTo(total);
        assertThat(meters.counter("trackline.analytics.projection.failures").count()).isEqualTo(failuresBefore + 1);
    }

    // --- the schema refuses an inconsistent placement ---------------------------------------

    @Test
    void theSchemaRefusesAnAssignedStatusWithNoDirection() {
        assertThatThrownBy(() -> insertObservation("bad1", "ASSIGNED_BY_BEARING", null, null, null, 4.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesAPositionOnAVehicleThatWasNotAssigned() {
        assertThatThrownBy(() -> insertObservation("bad2", "OFF_ROUTE", (short) 0, 100.0, 0.1, 400.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation("bad3", "UNKNOWN_DIRECTION", (short) 1, 100.0, 0.1, 4.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesAnUnprojectedRowCarryingAnyPlacement() {
        assertThatThrownBy(() -> insertObservation("bad4", null, (short) 0, 100.0, 0.1, 4.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaRefusesAnUnknownStatusAnImpossibleDirectionAndAFractionOutsideTheShape() {
        assertThatThrownBy(() -> insertObservation("bad5", "SOMEWHERE", null, null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation("bad6", "ASSIGNED_BY_BEARING", (short) 2, 100.0, 0.1, 4.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation("bad7", "ASSIGNED_BY_BEARING", (short) 0, 100.0, 1.5, 4.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObservation("bad8", "ASSIGNED_BY_BEARING", (short) 0, -5.0, 0.1, 4.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSchemaAcceptsEveryConsistentShape() {
        insertObservation("ok1", "ASSIGNED_BY_BEARING", (short) 0, 1234.5, 0.25, 6.0);
        insertObservation("ok2", "ASSIGNED_BY_MOVEMENT", (short) 1, 10.0, 0.01, 2.0);
        insertObservation("ok3", "UNKNOWN_DIRECTION", null, null, null, 12.0);
        insertObservation("ok4", "OFF_ROUTE", null, null, null, 612.0);
        insertObservation("ok5", "NO_ROUTE", null, null, null, null);
        insertObservation("ok6", "NO_GEOMETRY", null, null, null, null);
        insertObservation("ok7", null, null, null, null, null);

        Integer stored = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_observations WHERE vehicle_id LIKE 'ok%'", Integer.class);
        assertThat(stored).isEqualTo(7);
    }

    // --- helpers --------------------------------------------------------------------------------

    private void insertObservation(
            String vehicleId, String status, Short direction, Double progressMetres, Double fraction, Double offset) {
        Instant at = headerTimestampOf("vehicles.pb");
        observationWriter.ensurePartitionFor(at);
        Long run = jdbc.queryForObject(
                "INSERT INTO ingestion_runs (feed, started_at, duration_ms, status) "
                        + "VALUES ('VEHICLE_POSITIONS', now(), 0, 'SUCCESS') RETURNING id",
                Long.class);
        jdbc.update(
                """
                INSERT INTO vehicle_observations (
                    vehicle_id, source_timestamp, observed_at, position, ingestion_run_id,
                    projection_status, inferred_direction_id, progress_m, progress_fraction, projection_offset_m)
                VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(-79.38, 43.65), 4326)::geography, ?, ?, ?, ?, ?, ?)
                """,
                vehicleId, Timestamp.from(at), Timestamp.from(at), run,
                status, direction, progressMetres, fraction, offset == null ? null : offset.floatValue());
    }

    private Map<String, Integer> countsByStatus() {
        Map<String, Integer> counts = new java.util.HashMap<>();
        jdbc.query(
                "SELECT projection_status, count(*) AS n FROM vehicle_observations GROUP BY projection_status",
                rows -> {
                    counts.put(rows.getString("projection_status"), rows.getInt("n"));
                });
        return counts;
    }

    private static void serve(byte[] body) {
        feedServer.stubFor(WireMock.get("/gtfsrt/vehicles")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/x-google-protobuf")
                        .withBody(body)));
    }

    private static Instant headerTimestampOf(String fixture) {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(bytes(fixture));
            return Instant.ofEpochSecond(message.getHeader().getTimestamp());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }
}
