package ca.trackline.api.livestate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.ingestion.VehicleObservationWriter;
import ca.trackline.api.testsupport.RedisAndPostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live-state cache and the chain that takes over when it is gone.
 *
 * <p>The degradation path is the part of this layer most worth testing: it only
 * runs when something has already gone wrong, so it is the code least likely to be
 * exercised by hand and most likely to be broken when it is finally needed.
 */
class LiveStateIntegrationTest extends RedisAndPostgisIntegrationTest {

    private static final Instant FEED_TIME = Instant.parse("2026-10-03T12:00:00Z");

    @Autowired
    private LiveStateStore liveState;

    @Autowired
    private InProcessSnapshotCache inProcess;

    @Autowired
    private LiveVehicleReader reader;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VehicleObservationWriter observationWriter;

    @BeforeEach
    void clearEverything() {
        redis.delete(RedisLiveStateStore.VEHICLES_KEY);
        inProcess.forget();
        jdbc.execute("TRUNCATE vehicle_observations");
    }

    @AfterEach
    void clearUp() {
        redis.delete(RedisLiveStateStore.VEHICLES_KEY);
        inProcess.forget();
        jdbc.execute("TRUNCATE vehicle_observations");
    }

    @Test
    void aSnapshotSurvivesARoundTripThroughRedis() {
        liveState.replaceVehicles(List.of(vehicle("1234", "501", 43.65, -79.38), vehicle("5678", null, 43.70, -79.40)),
                FEED_TIME);

        LiveVehicles read = liveState.readVehicles().orElseThrow();

        assertThat(read.source()).isEqualTo(DataSource.LIVE);
        assertThat(read.degraded()).isFalse();
        assertThat(read.feedTimestamp()).isEqualTo(FEED_TIME);
        assertThat(read.vehicles()).hasSize(2);

        VehicleSnapshot first = read.vehicles().getFirst();
        assertThat(first.vehicleId()).isEqualTo("1234");
        assertThat(first.routeId()).isEqualTo("501");
        assertThat(first.latitude()).isEqualTo(43.65);
        // A vehicle with no route assignment must come back with none, not with an
        // empty string or a zero.
        assertThat(read.vehicles().get(1).routeId()).isNull();
    }

    @Test
    void optionalFieldsSurviveAsNullRatherThanAsZero() {
        VehicleSnapshot sparse = new VehicleSnapshot(
                "9999", null, null, 43.65, -79.38, null, null, null, null, null, null, FEED_TIME);
        liveState.replaceVehicles(List.of(sparse), FEED_TIME);

        VehicleSnapshot read = liveState.readVehicles().orElseThrow().vehicles().getFirst();

        // Round-tripping through JSON is where "the agency did not say" quietly
        // becomes "the agency said zero".
        assertThat(read.bearing()).isNull();
        assertThat(read.speed()).isNull();
        assertThat(read.occupancyStatus()).isNull();
        assertThat(read.stopId()).isNull();
    }

    @Test
    void writingReplacesRatherThanMergesTheSnapshot() {
        liveState.replaceVehicles(List.of(vehicle("a", "501", 43.6, -79.4), vehicle("b", "501", 43.6, -79.4)),
                FEED_TIME);
        liveState.replaceVehicles(List.of(vehicle("a", "501", 43.7, -79.4)), FEED_TIME.plusSeconds(20));

        LiveVehicles read = liveState.readVehicles().orElseThrow();

        // The feed publishes complete datasets, so a vehicle missing from the newest
        // payload has stopped reporting and must stop being shown.
        assertThat(read.vehicles()).hasSize(1);
        assertThat(read.vehicles().getFirst().vehicleId()).isEqualTo("a");
        assertThat(read.feedTimestamp()).isEqualTo(FEED_TIME.plusSeconds(20));
    }

    @Test
    void theSnapshotIsGivenATimeToLive() {
        liveState.replaceVehicles(List.of(vehicle("1234", "501", 43.65, -79.38)), FEED_TIME);

        Long ttlSeconds = redis.getExpire(RedisLiveStateStore.VEHICLES_KEY);

        // Without this, a dead ingester's last snapshot would be served forever as
        // though it were current.
        assertThat(ttlSeconds).isNotNull().isPositive();
        assertThat(Duration.ofSeconds(ttlSeconds)).isLessThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void anEmptyCacheIsReportedAsEmptyRatherThanAsNoVehicles() {
        assertThat(liveState.readVehicles()).isEmpty();
    }

    @Test
    void theReaderPrefersTheSharedCache() {
        liveState.replaceVehicles(List.of(vehicle("from-redis", "501", 43.65, -79.38)), FEED_TIME);
        inProcess.store(List.of(vehicle("from-memory", "501", 43.65, -79.38)), FEED_TIME);

        LiveVehicles read = reader.readAll();

        assertThat(read.source()).isEqualTo(DataSource.LIVE);
        assertThat(read.vehicles()).singleElement()
                .satisfies(v -> assertThat(v.vehicleId()).isEqualTo("from-redis"));
    }

    @Test
    void theReaderFallsBackToThisProcessWhenTheCacheIsEmpty() {
        inProcess.store(List.of(vehicle("from-memory", "501", 43.65, -79.38)), FEED_TIME);

        LiveVehicles read = reader.readAll();

        assertThat(read.source()).isEqualTo(DataSource.CACHED);
        assertThat(read.degraded()).isTrue();
        assertThat(read.vehicles()).singleElement()
                .satisfies(v -> assertThat(v.vehicleId()).isEqualTo("from-memory"));
    }

    @Test
    void theReaderFallsBackToStoredObservationsWhenNeitherCacheCanAnswer() {
        storeObservation("from-database", "501", 43.65, -79.38, Instant.now().minusSeconds(30));

        LiveVehicles read = reader.readAll();

        assertThat(read.source()).isEqualTo(DataSource.DB_FALLBACK);
        assertThat(read.degraded()).isTrue();
        assertThat(read.vehicles()).singleElement()
                .satisfies(v -> assertThat(v.vehicleId()).isEqualTo("from-database"));
    }

    @Test
    void theDatabaseFallbackReturnsOnlyEachVehiclesLatestReading() {
        Instant now = Instant.now();
        storeObservation("bus-1", "501", 43.60, -79.40, now.minusSeconds(120));
        storeObservation("bus-1", "501", 43.65, -79.38, now.minusSeconds(30));
        storeObservation("bus-2", "41", 43.70, -79.45, now.minusSeconds(60));

        LiveVehicles read = reader.readAll();

        assertThat(read.vehicles()).hasSize(2);
        assertThat(read.vehicles())
                .filteredOn(v -> v.vehicleId().equals("bus-1"))
                .singleElement()
                // The newer of the two readings, not both and not the older.
                .satisfies(v -> assertThat(v.latitude()).isEqualTo(43.65));
    }

    @Test
    void theDatabaseFallbackIgnoresVehiclesThatStoppedReportingLongAgo() {
        storeObservation("yesterday", "501", 43.65, -79.38, Instant.now().minus(Duration.ofHours(3)));

        // Showing it would place a bus somewhere it left hours ago.
        assertThat(reader.readAll().source()).isEqualTo(DataSource.NO_DATA);
    }

    @Test
    void nothingIngestedIsReportedAsNoDataRatherThanAsAnEmptyFleet() {
        LiveVehicles read = reader.readAll();

        assertThat(read.source()).isEqualTo(DataSource.NO_DATA);
        assertThat(read.vehicles()).isEmpty();
        assertThat(read.feedTimestamp()).isNull();
        // Not degraded: nothing failed, there is simply nothing yet.
        assertThat(read.degraded()).isFalse();
    }

    @Test
    void filteringByRouteKeepsTheProvenanceOfTheAnswer() {
        liveState.replaceVehicles(
                List.of(vehicle("a", "501", 43.65, -79.38), vehicle("b", "41", 43.70, -79.40)), FEED_TIME);

        LiveVehicles read = reader.readForRoute("501");

        assertThat(read.vehicles()).singleElement().satisfies(v -> assertThat(v.vehicleId()).isEqualTo("a"));
        assertThat(read.source()).isEqualTo(DataSource.LIVE);
        assertThat(read.feedTimestamp()).isEqualTo(FEED_TIME);
    }

    @Test
    void filteringByBoundingBoxKeepsOnlyWhatIsInside() {
        liveState.replaceVehicles(
                List.of(
                        vehicle("inside", "501", 43.65, -79.38),
                        vehicle("north", "501", 43.90, -79.38),
                        vehicle("west", "501", 43.65, -79.70)),
                FEED_TIME);

        LiveVehicles read = reader.readInBoundingBox(-79.45, 43.60, -79.30, 43.70);

        assertThat(read.vehicles()).singleElement()
                .satisfies(v -> assertThat(v.vehicleId()).isEqualTo("inside"));
    }

    private static VehicleSnapshot vehicle(String id, String routeId, double latitude, double longitude) {
        return new VehicleSnapshot(
                id, routeId, null, latitude, longitude, 90f, 5f, (short) 1, 3, "1234", (short) 1, FEED_TIME);
    }

    private void storeObservation(String vehicleId, String routeId, double lat, double lon, Instant at) {
        jdbc.update(
                """
                INSERT INTO ingestion_runs (feed, started_at, duration_ms, status, entity_count)
                VALUES ('VEHICLE_POSITIONS', now(), 1, 'SUCCESS', 1)
                ON CONFLICT DO NOTHING
                """);
        Long runId = jdbc.queryForObject("SELECT max(id) FROM ingestion_runs", Long.class);

        // The same partitioning the application uses, which covers the day either side. A
        // partition built here from the UTC date alone did not hold a reading from "three
        // hours ago" when run between 20:00 and 03:00 in Toronto, where that instant falls on
        // the previous local day while the database reads partition bounds as local midnight.
        observationWriter.ensurePartitionFor(at);

        jdbc.update(
                """
                INSERT INTO vehicle_observations (
                    vehicle_id, source_timestamp, observed_at, route_id, position, ingestion_run_id)
                VALUES (?, ?, now(), ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                ON CONFLICT DO NOTHING
                """,
                vehicleId, java.sql.Timestamp.from(at), routeId, lon, lat, runId);
    }

}
