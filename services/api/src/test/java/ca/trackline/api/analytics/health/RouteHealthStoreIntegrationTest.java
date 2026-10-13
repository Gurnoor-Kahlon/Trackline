package ca.trackline.api.analytics.health;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.SpacingBasis;
import ca.trackline.api.analytics.health.RouteHealthStore.HealthRead;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.testsupport.RedisAndPostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared cache for route health: that what goes in comes back whole, that it expires, and
 * that losing the shared tier falls back to this process's own copy and says so.
 */
class RouteHealthStoreIntegrationTest extends RedisAndPostgisIntegrationTest {

    private static final Instant FEED = Instant.parse("2026-10-12T14:00:00Z");

    @Autowired
    private RouteHealthStore store;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void clean() {
        redis.delete(RouteHealthStore.KEY);
        store.forget();
    }

    private static HealthSnapshot snapshot() {
        HealthMetrics metrics = new HealthMetrics(
                5, 4, 3000.0, 4200.0, 0.31, 1.4, 3000.0, 6.5, 0.77, 600.0, 5.0, 1, 2, SpacingBasis.SCHEDULE);
        DirectionHealth direction = new DirectionHealth(
                (short) 0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics);
        // A direction with no verdict carries nulls, which must survive the round trip as nulls.
        DirectionHealth unknown = new DirectionHealth(
                (short) 1, RouteHealthState.UNKNOWN, HealthConfidence.NONE, List.of(ReasonCode.INSUFFICIENT_VEHICLES),
                new HealthMetrics(1, 0, null, null, null, null, null, null, null, null, null, 0, 0, SpacingBasis.OBSERVED));
        RouteHealth route = new RouteHealth(
                "501", RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), List.of(direction, unknown), 2);
        return new HealthSnapshot(FEED, FEED.plusSeconds(3), true, List.of(route));
    }

    @Test
    void nothingStoredReadsAsNoDataRatherThanAsAnEmptyNetwork() {
        HealthRead read = store.read();

        assertThat(read.snapshot()).isNull();
        assertThat(read.source()).isEqualTo(DataSource.NO_DATA);
        assertThat(read.degraded()).isFalse();
    }

    @Test
    void aPublishedSnapshotComesBackFromRedisUnchanged() {
        HealthSnapshot published = snapshot();

        store.publish(published);
        HealthRead read = store.read();

        assertThat(read.source()).isEqualTo(DataSource.LIVE);
        assertThat(read.degraded()).isFalse();
        assertThat(read.snapshot()).isEqualTo(published);
    }

    @Test
    void absentFiguresStayAbsentAndAreNotTurnedIntoZeros() {
        store.publish(snapshot());

        DirectionHealth unknown = store.read().snapshot().routes().getFirst().directions().get(1);

        assertThat(unknown.metrics().meanGapMetres()).isNull();
        assertThat(unknown.metrics().variation()).isNull();
        assertThat(unknown.metrics().supplyRatio()).isNull();
    }

    @Test
    void theSnapshotIsGivenAnExpiryAsEverythingInRedisIs() {
        store.publish(snapshot());

        Long seconds = redis.getExpire(RouteHealthStore.KEY);

        // A stopped ingester's last verdicts must not be served forever as though current.
        assertThat(seconds).isNotNull().isPositive();
        assertThat(Duration.ofSeconds(seconds)).isLessThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void aNewSnapshotReplacesTheOldOneWholesale() {
        store.publish(snapshot());
        HealthSnapshot next = new HealthSnapshot(FEED.plusSeconds(20), FEED.plusSeconds(23), true, List.of());

        store.publish(next);

        assertThat(store.read().snapshot().routes()).isEmpty();
        assertThat(store.read().snapshot().feedTimestamp()).isEqualTo(FEED.plusSeconds(20));
    }

    @Test
    void whenRedisHasLostTheKeyThisProcessesOwnCopyAnswersAndIsMarkedDegraded() {
        HealthSnapshot published = snapshot();
        store.publish(published);
        redis.delete(RouteHealthStore.KEY);

        HealthRead read = store.read();

        assertThat(read.snapshot()).isEqualTo(published);
        assertThat(read.source()).isEqualTo(DataSource.CACHED);
        assertThat(read.degraded()).isTrue();
    }

    @Test
    void anUnreadableDocumentInRedisFallsBackInsteadOfFailingTheRequest() {
        HealthSnapshot published = snapshot();
        store.publish(published);
        redis.opsForValue().set(RouteHealthStore.KEY, "{ this is not a snapshot");

        HealthRead read = store.read();

        assertThat(read.snapshot()).isEqualTo(published);
        assertThat(read.source()).isEqualTo(DataSource.CACHED);
    }
}
