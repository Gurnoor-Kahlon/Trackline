package ca.trackline.api.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ca.trackline.api.alerts.ActiveAlerts;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static ca.trackline.api.testsupport.FeedFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole cycle against a feed server that can be made to misbehave on cue.
 *
 * <p>These are the cases that decide whether one bad cycle costs a data point or
 * the whole service: a publisher that times out, returns an error page, serves the
 * same payload twice, or suddenly serves almost nothing. None of them can be
 * provoked against the real TTC, and all of them will happen.
 */
class IngestionCycleIntegrationTest extends PostgisIntegrationTest {

    private static WireMockServer feedServer;

    @Autowired
    private IngestionCycleService cycleService;

    @Autowired
    private VehicleObservationWriter observationWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ActiveAlerts activeAlerts;

    /**
     * Fixed to the moment the fixtures were recorded.
     *
     * <p>Without this the test rots: the payloads carry real timestamps, the
     * validator rejects readings older than six hours, and a suite that passed when
     * the fixtures were captured starts failing the next day for a reason that has
     * nothing to do with the code. Recording "now" alongside the data is what makes
     * a recorded feed reusable.
     */
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
    void reset() {
        Instant recordedAt = headerTimestampOf("vehicles.pb");
        org.mockito.Mockito.when(clock.instant()).thenReturn(recordedAt);
        org.mockito.Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        feedServer.resetAll();
        observationWriter.forgetAll();
        jdbc.execute("TRUNCATE vehicle_observations");
        jdbc.execute("DELETE FROM ingestion_runs");
        jdbc.execute("TRUNCATE service_alerts CASCADE");
        resetCycleMemory();
    }

    @AfterEach
    void clearUp() {
        jdbc.execute("TRUNCATE vehicle_observations");
        jdbc.execute("DELETE FROM ingestion_runs");
        jdbc.execute("TRUNCATE service_alerts CASCADE");
    }

    @Test
    void aSuccessfulCycleStoresObservationsAndRecordsTheRun() {
        serve("vehicles", bytes("vehicles.pb"));

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(result.stored()).isPositive();
        assertThat(count("vehicle_observations")).isEqualTo(result.stored());

        assertThat(runs()).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("SUCCESS");
            assertThat(run.entityCount()).isPositive();
            assertThat(run.feedHeaderTimestampPresent()).isTrue();
        });
    }

    @Test
    void storedObservationsCarryThePublishedValuesAndNothingInvented() {
        serve("vehicles", bytes("vehicles.pb"));
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        Integer withoutPosition = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_observations WHERE position IS NULL", Integer.class);
        assertThat(withoutPosition).isZero();

        // Vehicles with no trip assignment are stored with a null route rather than
        // dropped or attributed to something.
        Integer unassigned = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_observations WHERE route_id IS NULL", Integer.class);
        assertThat(unassigned).isPositive();

        Integer outsideToronto = jdbc.queryForObject(
                """
                SELECT count(*) FROM vehicle_observations
                WHERE ST_Y(position::geometry) NOT BETWEEN 43.0 AND 44.5
                   OR ST_X(position::geometry) NOT BETWEEN -80.0 AND -78.5
                """,
                Integer.class);
        assertThat(outsideToronto).isZero();
    }

    @Test
    void anUnchangedPayloadIsRecordedWithoutBeingReprocessed() {
        serve("vehicles", bytes("vehicles.pb"));

        IngestionCycleResult first = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        IngestionCycleResult second = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(first.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        // The publisher advances its header about every 20 seconds, so most polls see
        // what the last one saw. Recording that is what keeps a stalled publisher
        // distinguishable from a healthy quiet one.
        assertThat(second.status()).isEqualTo(IngestionRunStatus.NO_CHANGE);
        assertThat(second.stored()).isZero();
        assertThat(count("vehicle_observations")).isEqualTo(first.stored());
        assertThat(runs()).hasSize(2);
    }

    @Test
    void aMalformedPayloadFailsTheCycleAndKeepsWhatWasStored() {
        serve("vehicles", bytes("vehicles.pb"));
        int storedByGoodCycle = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS).stored();

        feedServer.resetAll();
        serve("vehicles", bytes("malformed.pb"));
        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // A partly-parsed complete dataset describes an unknown subset of the
        // network, so the previous snapshot is strictly better than replacing it.
        assertThat(result.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(result.failureReason()).isNotBlank();
        assertThat(count("vehicle_observations")).isEqualTo(storedByGoodCycle);
        assertThat(runs()).last().satisfies(run -> assertThat(run.status()).isEqualTo("FAILED"));
    }

    @Test
    void aServerErrorFailsTheCycleWithoutThrowingOutOfIt() {
        feedServer.stubFor(WireMock.get("/gtfsrt/vehicles")
                .willReturn(WireMock.aResponse().withStatus(503).withBody("upstream unavailable")));

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(result.failureReason()).contains("503");
        assertThat(runs()).singleElement().satisfies(run -> assertThat(run.status()).isEqualTo("FAILED"));
    }

    @Test
    void aStalledPublisherFailsTheCycleRatherThanHoldingItOpen() {
        feedServer.stubFor(WireMock.get("/gtfsrt/vehicles")
                .willReturn(WireMock.aResponse()
                        .withFixedDelay((int) Duration.ofSeconds(20).toMillis())
                        .withBody(bytes("vehicles.pb"))));

        long started = System.nanoTime();
        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(result.status()).isEqualTo(IngestionRunStatus.FAILED);
        // The read timeout is five seconds. Without it a stalled feed would hold the
        // cycle open past the next poll and every one after it.
        assertThat(elapsedMs).isLessThan(15_000);
    }

    @Test
    void anEmptyPayloadIsTreatedAsSuspectRatherThanAsAnEmptyCity() {
        serve("vehicles", bytes("vehicles.pb"));
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        feedServer.resetAll();
        // The header has to move on, or the cycle correctly stops at "nothing new"
        // before it ever assesses the payload.
        serve("vehicles", withAdvancedHeader(bytes("empty.pb")));
        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // Toronto does not lose its entire fleet between two polls. Marking this
        // suspect is what stops an empty feed being presented as "no service".
        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUSPECT);
    }

    @Test
    void onlyOneCyclePerFeedRunsAtATime() throws Exception {
        feedServer.stubFor(WireMock.get("/gtfsrt/vehicles")
                .willReturn(WireMock.aResponse().withFixedDelay(1_500).withBody(bytes("vehicles.pb"))));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        try {
            for (int index = 0; index < threads; index++) {
                pool.submit(() -> {
                    startLine.await();
                    IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
                    if (result.ran()) {
                        ran.incrementAndGet();
                    } else {
                        skipped.incrementAndGet();
                    }
                    return null;
                });
            }
            startLine.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Overlapping cycles would duplicate the work and race each other's
        // deduplication state. A slow fetch makes overlap likely, not theoretical.
        assertThat(ran.get()).isEqualTo(1);
        assertThat(skipped.get()).isEqualTo(threads - 1);
        assertThat(runs()).hasSize(1);
    }

    @Test
    void aStationaryVehicleIsNotStoredOnEveryCycle() {
        serve("vehicles", bytes("vehicles.pb"));
        int first = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS).stored();

        // Same payload, new header, so the cycle is processed rather than skipped as
        // unchanged; the vehicles themselves have not moved or re-reported.
        feedServer.resetAll();
        serve("vehicles", withAdvancedHeader(bytes("vehicles.pb")));
        int second = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS).stored();

        assertThat(first).isPositive();
        // Unfiltered this table would grow by 1,300 rows every ten seconds, most of
        // them recording vehicles standing still.
        assertThat(second).isZero();
    }

    @Test
    void tripUpdatesAndAlertsAreIngestedWithoutStoringObservations() {
        serve("trips", bytes("trip-updates.pb"));
        serve("alerts", bytes("alerts.pb"));

        IngestionCycleResult trips = cycleService.runCycle(RealtimeFeed.TRIP_UPDATES);
        IngestionCycleResult alerts = cycleService.runCycle(RealtimeFeed.ALERTS);

        assertThat(trips.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(alerts.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(count("vehicle_observations")).isZero();

        assertThat(runs()).hasSize(2);
        assertThat(runs()).allSatisfy(run -> assertThat(run.entityCount()).isPositive());
    }

    // --- alerts -------------------------------------------------------------------------------------

    private int alertRows() {
        return count("service_alerts");
    }

    private int openAlertRows() {
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM service_alerts WHERE resolved_at IS NULL", Integer.class);
        return value == null ? -1 : value;
    }

    private Instant lastSeen() {
        return jdbc.queryForObject("SELECT max(last_seen_at) FROM service_alerts", java.sql.Timestamp.class)
                .toInstant();
    }

    @Test
    void anAlertsCycleStoresTheAlertsAndCountsThem() {
        serve("alerts", bytes("alerts.pb"));

        IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.ALERTS);

        assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(alertRows()).isEqualTo(result.snapshot().alerts().size()).isPositive();
        assertThat(openAlertRows()).isEqualTo(alertRows());
        assertThat(activeAlerts.current()).isEqualTo(alertRows());
        assertThat(count("service_alert_entities")).isPositive();
    }

    @Test
    void aLaterCycleOfTheSamePayloadConfirmsAlertsInsteadOfDuplicatingThem() {
        serve("alerts", bytes("alerts.pb"));
        cycleService.runCycle(RealtimeFeed.ALERTS);
        int stored = alertRows();
        Instant firstCycle = lastSeen();

        org.mockito.Mockito.when(clock.instant()).thenReturn(firstCycle.plusSeconds(60));
        serve("alerts", withAdvancedHeader(bytes("alerts.pb")));
        IngestionCycleResult second = cycleService.runCycle(RealtimeFeed.ALERTS);

        assertThat(second.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(alertRows()).isEqualTo(stored);
        assertThat(lastSeen()).isEqualTo(firstCycle.plusSeconds(60));
    }

    @Test
    void aPayloadTheFeedHasNotChangedStillCountsAsSeeingItsAlerts() {
        serve("alerts", bytes("alerts.pb"));
        cycleService.runCycle(RealtimeFeed.ALERTS);
        Instant firstCycle = lastSeen();

        org.mockito.Mockito.when(clock.instant()).thenReturn(firstCycle.plusSeconds(60));
        IngestionCycleResult second = cycleService.runCycle(RealtimeFeed.ALERTS);

        // The header has not advanced, so nothing is reprocessed, but the alerts were fetched
        // again and are still there, which is what last_seen_at records.
        assertThat(second.status()).isEqualTo(IngestionRunStatus.NO_CHANGE);
        assertThat(lastSeen()).isEqualTo(firstCycle.plusSeconds(60));
    }

    @Test
    void aFeedThatSuddenlyEmptiesIsNotBelievedAtOnceButIsOnceItStaysEmpty() {
        serve("alerts", bytes("alerts.pb"));
        cycleService.runCycle(RealtimeFeed.ALERTS);
        int stored = alertRows();

        // The recorded payloads share one capture time, so the header is moved past the alerts
        // payload's to make each of these a new publication and not a repeat of it.
        byte[] emptied = withAdvancedHeader(bytes("empty.pb"));
        serve("alerts", emptied);
        IngestionCycleResult first = cycleService.runCycle(RealtimeFeed.ALERTS);

        // From some alerts to none in one cycle is what a broken feed looks like, so it is
        // suspect and nothing is ended on its word.
        assertThat(first.status()).isEqualTo(IngestionRunStatus.SUSPECT);
        assertThat(openAlertRows()).isEqualTo(stored);
        assertThat(activeAlerts.current()).isEqualTo(stored);

        serve("alerts", withAdvancedHeader(emptied));
        IngestionCycleResult second = cycleService.runCycle(RealtimeFeed.ALERTS);

        // Empty twice running, measured against the new baseline, is believed.
        assertThat(second.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        assertThat(openAlertRows()).isZero();
        assertThat(alertRows()).isEqualTo(stored);
        assertThat(activeAlerts.current()).isZero();
    }

    @Test
    void aFailedFetchLeavesTheStoredAlertsAlone() {
        serve("alerts", bytes("alerts.pb"));
        cycleService.runCycle(RealtimeFeed.ALERTS);
        int stored = openAlertRows();

        feedServer.stubFor(WireMock.get("/gtfsrt/alerts").willReturn(WireMock.aResponse().withStatus(503)));
        IngestionCycleResult failed = cycleService.runCycle(RealtimeFeed.ALERTS);

        assertThat(failed.status()).isEqualTo(IngestionRunStatus.FAILED);
        assertThat(openAlertRows()).isEqualTo(stored);
    }

    /** When a recorded payload says it was published. */
    private static Instant headerTimestampOf(String fixture) {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(bytes(fixture));
            return Instant.ofEpochSecond(message.getHeader().getTimestamp());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Rewrites the header timestamp so an otherwise identical payload counts as new.
     * The header is the first field of the message, so bumping the seconds value is
     * enough without re-encoding the entities.
     */
    private static byte[] withAdvancedHeader(byte[] payload) {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(payload);
            return message.toBuilder()
                    .setHeader(message.getHeader().toBuilder()
                            .setTimestamp(message.getHeader().getTimestamp() + 30))
                    .build()
                    .toByteArray();
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private static void serve(String path, byte[] body) {
        feedServer.stubFor(WireMock.get("/gtfsrt/" + path)
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/x-google-protobuf")
                        .withBody(body)));
    }

    /**
     * The cycle service remembers the last header per feed across calls, which is
     * exactly what makes the unchanged-payload check work. Tests need that memory
     * cleared between cases.
     */
    private void resetCycleMemory() {
        cycleService.forgetFeedState();
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? -1 : value;
    }

    private List<RunRow> runs() {
        return jdbc.query(
                "SELECT status, entity_count, feed_header_timestamp FROM ingestion_runs ORDER BY id",
                (rows, index) -> new RunRow(
                        rows.getString("status"),
                        rows.getInt("entity_count"),
                        rows.getTimestamp("feed_header_timestamp") != null));
    }

    private record RunRow(String status, int entityCount, boolean feedHeaderTimestampPresent) {
    }
}
