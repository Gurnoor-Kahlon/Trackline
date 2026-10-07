package ca.trackline.api.persistence;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.ingestion.VehicleObservationWriter;
import ca.trackline.api.persistence.query.PreviousReadingRepository;
import ca.trackline.api.persistence.query.PreviousReadingRepository.PreviousReading;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Which earlier reading does a vehicle get compared with?
 *
 * <p>The answer decides direction for every vehicle whose heading is inconclusive,
 * so the edges matter: a reading taken at the same instant is not "previous", one
 * from an hour ago is not recent enough to say which way a bus is going now, and
 * vehicles must not borrow each other's history.
 */
class PreviousReadingRepositoryIntegrationTest extends PostgisIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-02T04:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    @Autowired
    private PreviousReadingRepository repository;

    @Autowired
    private VehicleObservationWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    private long runId;

    @BeforeEach
    void setUp() {
        clear(jdbc);
        writer.ensurePartitionFor(NOW);
        runId = jdbc.queryForObject(
                "INSERT INTO ingestion_runs (feed, started_at, duration_ms, status) "
                        + "VALUES ('VEHICLE_POSITIONS', now(), 0, 'SUCCESS') RETURNING id",
                Long.class);
    }

    @AfterEach
    void tearDown() {
        clear(jdbc);
    }

    private void stored(String vehicleId, String routeId, Instant at, double latitude, double longitude) {
        jdbc.update(
                """
                INSERT INTO vehicle_observations (
                    vehicle_id, source_timestamp, observed_at, route_id, position, ingestion_run_id)
                VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                """,
                vehicleId, Timestamp.from(at), Timestamp.from(at), routeId, longitude, latitude, runId);
    }

    private static VehicleSnapshot reading(String vehicleId, Instant at) {
        return new VehicleSnapshot(vehicleId, "501", null, 43.65, -79.38, 90f, 5f, null, null, null, null, at);
    }

    @Test
    void theMostRecentEarlierReadingIsTheOneReturned() {
        stored("v", "501", NOW.minusSeconds(240), 43.650, -79.400);
        stored("v", "501", NOW.minusSeconds(120), 43.651, -79.395);
        stored("v", "501", NOW.minusSeconds(30), 43.652, -79.390);

        Map<String, PreviousReading> found = repository.latestBefore(List.of(reading("v", NOW)), MAX_AGE);

        PreviousReading previous = found.get("v");
        assertThat(previous.timestamp()).isEqualTo(NOW.minusSeconds(30));
        assertThat(previous.latitude()).isCloseTo(43.652, within(1e-9));
        assertThat(previous.longitude()).isCloseTo(-79.390, within(1e-9));
        assertThat(previous.routeId()).isEqualTo("501");
    }

    @Test
    void aReadingAtTheSameInstantIsTheReadingItselfNotAPreviousOne() {
        stored("v", "501", NOW, 43.65, -79.38);

        assertThat(repository.latestBefore(List.of(reading("v", NOW)), MAX_AGE)).isEmpty();
    }

    @Test
    void aReadingFromTheFutureOfTheOneBeingPlacedIsNeverReturned() {
        stored("v", "501", NOW.plusSeconds(20), 43.65, -79.38);

        assertThat(repository.latestBefore(List.of(reading("v", NOW)), MAX_AGE)).isEmpty();
    }

    @Test
    void aReadingOlderThanTheLimitIsTooStaleToSayWhichWayTheVehicleIsGoing() {
        stored("v", "501", NOW.minus(MAX_AGE).minusSeconds(1), 43.65, -79.40);

        assertThat(repository.latestBefore(List.of(reading("v", NOW)), MAX_AGE)).isEmpty();
    }

    @Test
    void aReadingExactlyAtTheLimitStillCounts() {
        stored("v", "501", NOW.minus(MAX_AGE), 43.65, -79.40);

        assertThat(repository.latestBefore(List.of(reading("v", NOW)), MAX_AGE)).containsKey("v");
    }

    @Test
    void theLimitIsMeasuredFromEachVehiclesOwnReadingNotFromTheNewestInTheBatch() {
        // "slow" last reported four minutes before "fast", and its earlier reading is
        // four and a half minutes before its own reading — inside the limit for it,
        // though well outside it measured from the newest timestamp in the batch.
        Instant slowNow = NOW.minusSeconds(240);
        stored("slow", "501", slowNow.minusSeconds(270), 43.65, -79.40);

        Map<String, PreviousReading> found =
                repository.latestBefore(List.of(reading("fast", NOW), reading("slow", slowNow)), MAX_AGE);

        assertThat(found).containsOnlyKeys("slow");
    }

    @Test
    void vehiclesDoNotBorrowEachOthersHistory() {
        stored("a", "501", NOW.minusSeconds(30), 43.650, -79.400);
        stored("b", "501", NOW.minusSeconds(30), 43.660, -79.300);

        Map<String, PreviousReading> found =
                repository.latestBefore(List.of(reading("a", NOW), reading("b", NOW), reading("c", NOW)), MAX_AGE);

        assertThat(found).containsOnlyKeys("a", "b");
        assertThat(found.get("a").longitude()).isCloseTo(-79.400, within(1e-9));
        assertThat(found.get("b").longitude()).isCloseTo(-79.300, within(1e-9));
    }

    @Test
    void theRouteTheVehicleWasWorkingIsReportedSoTheCallerCanDiscardAChange() {
        stored("v", "29", NOW.minusSeconds(30), 43.65, -79.40);

        assertThat(repository.latestBefore(List.of(reading("v", NOW)), MAX_AGE).get("v").routeId()).isEqualTo("29");
    }

    @Test
    void anEmptyBatchMakesNoQueryAndReturnsNothing() {
        assertThat(repository.latestBefore(List.of(), MAX_AGE)).isEmpty();
    }
}
