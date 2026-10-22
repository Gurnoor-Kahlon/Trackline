package ca.trackline.api.livestate;

import java.time.Duration;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the circuit breaker does to a Redis that really goes away.
 *
 * <p>{@link RedisOutageIntegrationTest} shows the API stays up and honest without Redis. This shows what it costs: after a few
 * failed calls Redis is left alone, and a read that used to wait out a timeout reaches its fallback at once.
 *
 * <p>Its own Redis, because the outage cannot be undone cheaply, and ordered for the same reason.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCircuitBreakerOutageIntegrationTest {

    private static final GenericContainer<?> DISPOSABLE_REDIS = TestContainers.startDisposableRedis();
    private static final Instant FEED_TIME = Instant.now().minusSeconds(10);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", DISPOSABLE_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> DISPOSABLE_REDIS.getMappedPort(TestContainers.REDIS_PORT));
        // Long enough that the circuit stays open for the whole test, so what it shows does not depend on how fast the machine is.
        registry.add("trackline.live-state.breaker-open-for", () -> "10m");
    }

    @Autowired
    private LiveStateStore liveState;

    @Autowired
    private RedisCircuitBreaker breaker;

    @Autowired
    private InProcessSnapshotCache inProcess;

    @Autowired
    private LiveVehicleReader reader;

    @AfterAll
    static void disposeOfTheContainer() {
        DISPOSABLE_REDIS.stop();
    }

    @Test
    @Order(1)
    void aWorkingRedisKeepsTheCircuitClosed() {
        liveState.replaceVehicles(List.of(vehicle("1")), FEED_TIME);

        assertThat(liveState.readVehicles()).isPresent();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    @Order(2)
    void aRedisThatHasGoneOpensTheCircuitAfterAFewFailures() {
        DISPOSABLE_REDIS.stop();

        for (int attempt = 0; attempt < 5 && !breaker.isOpen(); attempt++) {
            assertThat(liveState.readVehicles()).isEmpty();
        }

        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    @Order(3)
    void withTheCircuitOpenReadsAndWritesReachTheirFallbackAtOnce() {
        long refusedBefore = breaker.refusedCalls();
        long started = System.nanoTime();

        for (int call = 0; call < 50; call++) {
            liveState.replaceVehicles(List.of(vehicle("2")), FEED_TIME);
            assertThat(liveState.readVehicles()).isEmpty();
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        // A hundred calls against a Redis that times out would take about fifty seconds at 500 ms each.
        assertThat(elapsedMillis).as("a hundred calls with the circuit open").isLessThan(2_000);
        assertThat(breaker.refusedCalls() - refusedBefore).isEqualTo(100);
    }

    @Test
    @Order(4)
    void theFallbackStillAnswersAndSaysItIsDegraded() {
        inProcess.store(List.of(vehicle("3")), FEED_TIME);

        LiveVehicles answer = reader.readAll();

        assertThat(answer.vehicles()).hasSize(1);
        assertThat(answer.source()).isEqualTo(DataSource.CACHED);
        assertThat(answer.degraded()).isTrue();
    }

    private static VehicleSnapshot vehicle(String id) {
        return new VehicleSnapshot(
                id, "501", null, 43.65, -79.38, 90f, 0f, (short) 1, 2, "1234", (short) 1, FEED_TIME);
    }
}
