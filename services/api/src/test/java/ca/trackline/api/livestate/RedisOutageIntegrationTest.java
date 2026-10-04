package ca.trackline.api.livestate;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.testsupport.TestContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens when Redis actually goes away.
 *
 * <p>The container is stopped partway through, which no mock can do. Everything
 * here is about the promise the API makes to its clients: a cache outage degrades
 * the answer and says so, and never produces an error page. A map that keeps
 * drawing from a slightly older snapshot is a far better outcome than a blank
 * screen, but only if the screen is honest about which it is showing.
 *
 * <p>Ordered on purpose. The container cannot be restarted cheaply, so the healthy
 * case is asserted first and the outage is irreversible for the rest of the class.
 *
 * <p>This class starts its own Redis rather than using the shared one. Stopping the
 * shared container would fail every class scheduled afterwards for a reason that
 * has nothing to do with what it was testing — a trap that would only appear once
 * the suite grew or the order changed.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
class RedisOutageIntegrationTest {

    private static final GenericContainer<?> DISPOSABLE_REDIS = TestContainers.startDisposableRedis();

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", DISPOSABLE_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> DISPOSABLE_REDIS.getMappedPort(TestContainers.REDIS_PORT));
    }

    private static final Instant FEED_TIME = Instant.now().minusSeconds(10);

    @Autowired
    private LiveStateStore liveState;

    @Autowired
    private InProcessSnapshotCache inProcess;

    @Autowired
    private LiveVehicleReader reader;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void disposeOfTheContainer() {
        // This class owns it, so this class cleans it up.
        DISPOSABLE_REDIS.stop();
    }

    @Test
    @Order(1)
    void withRedisUpTheApiServesLiveData() throws Exception {
        liveState.replaceVehicles(List.of(vehicle("1234", "501")), FEED_TIME);

        mockMvc.perform(get("/api/v1/vehicles").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.degraded").value(false));
    }

    @Test
    @Order(2)
    void withRedisDownTheApiKeepsServingAndSaysItIsDegraded() throws Exception {
        inProcess.store(List.of(vehicle("1234", "501"), vehicle("5678", "41")), FEED_TIME);
        DISPOSABLE_REDIS.stop();

        mockMvc.perform(get("/api/v1/vehicles").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2))
                // The client is told the answer came from a fallback rather than
                // being left to assume it is as current as usual.
                .andExpect(jsonPath("$.meta.source").value("CACHED"))
                .andExpect(jsonPath("$.meta.degraded").value(true))
                .andExpect(jsonPath("$.meta.ageSeconds").isNumber());
    }

    @Test
    @Order(3)
    void writingToAStoppedCacheDoesNotThrow() {
        // Ingestion must carry on through a cache outage: history still has to be
        // recorded, and the cycle must not fail because an optional tier is gone.
        liveState.replaceVehicles(List.of(vehicle("9999", "501")), FEED_TIME);

        assertThat(liveState.readVehicles()).isEmpty();
    }

    @Test
    @Order(4)
    void readingFromAStoppedCacheIsAnEmptyAnswerNotAnException() {
        assertThat(liveState.readVehicles()).isEmpty();
    }

    @Test
    @Order(5)
    void withBothCachesGoneTheDatabaseStillAnswers() throws Exception {
        inProcess.forget();
        storeObservation("from-database", "501");

        mockMvc.perform(get("/api/v1/vehicles").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicles[0].vehicleId").value("from-database"))
                .andExpect(jsonPath("$.meta.source").value("DB_FALLBACK"))
                .andExpect(jsonPath("$.meta.degraded").value(true));

        assertThat(reader.readAll().source()).isEqualTo(DataSource.DB_FALLBACK);
    }

    @Test
    @Order(6)
    void withEverythingGoneTheApiReportsNoDataRatherThanFailing() throws Exception {
        inProcess.forget();
        jdbc.execute("TRUNCATE vehicle_observations");

        // The worst case still has to be a 200 with an honest meta block. A 500 here
        // would take the map down for a condition the system can describe perfectly
        // well.
        mockMvc.perform(get("/api/v1/vehicles").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.ageSeconds").isEmpty());
    }

    @Test
    @Order(7)
    void healthStaysUpWhileTheCacheIsDown() throws Exception {
        // Redis is a cache whose absence degrades responses rather than breaking
        // them. Reporting the service unhealthy would pull an instance that can
        // still serve out of a load balancer.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private static VehicleSnapshot vehicle(String id, String routeId) {
        return new VehicleSnapshot(
                id, routeId, null, 43.65, -79.38, 90f, 0f, (short) 1, 2, "1234", (short) 1, FEED_TIME);
    }

    private void storeObservation(String vehicleId, String routeId) {
        jdbc.update("""
                INSERT INTO ingestion_runs (feed, started_at, duration_ms, status, entity_count)
                VALUES ('VEHICLE_POSITIONS', now(), 1, 'SUCCESS', 1)
                """);
        Long runId = jdbc.queryForObject("SELECT max(id) FROM ingestion_runs", Long.class);
        Instant at = Instant.now().minusSeconds(20);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS vehicle_observations_%s PARTITION OF vehicle_observations
                FOR VALUES FROM ('%s') TO ('%s')
                """.formatted(
                at.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString().replace('-', '_'),
                at.atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                at.atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1)));

        jdbc.update(
                """
                INSERT INTO vehicle_observations (
                    vehicle_id, source_timestamp, observed_at, route_id, position, ingestion_run_id)
                VALUES (?, ?, now(), ?, ST_SetSRID(ST_MakePoint(-79.38, 43.65), 4326)::geography, ?)
                ON CONFLICT DO NOTHING
                """,
                vehicleId, java.sql.Timestamp.from(at), routeId, runId);
    }
}
