package ca.trackline.api.analytics.health;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.bunching.BunchingModels.DirectionSpacing;
import ca.trackline.api.analytics.bunching.BunchingModels.PlacedVehicle;
import ca.trackline.api.analytics.bunching.BunchingModels.RouteSpacing;
import ca.trackline.api.analytics.bunching.BunchingModels.SpacingSnapshot;
import ca.trackline.api.analytics.bunching.BunchingModels.VehicleRole;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import ca.trackline.api.analytics.health.RouteHealthStore.HealthRead;
import ca.trackline.api.analytics.health.RouteHealthStore.SpacingRead;
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
        redis.delete(RouteHealthStore.SPACING_KEY);
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

    // --- the spacing behind the verdicts -----------------------------------------------------------------------

    private static SpacingSnapshot spacing() {
        BunchingCandidate bunch = new BunchingCandidate(
                "501", (short) 0, List.of("a", "b"), 412.5, 3000.0, 6500.0, BunchingSeverity.SEVERE, SpacingBasis.SCHEDULE);
        DirectionSpacing direction = new DirectionSpacing(
                (short) 0, 17_400.0, false, 3000.0, SpacingBasis.SCHEDULE,
                List.of(new PlacedVehicle("a", 1000.0, VehicleRole.BUNCHED), new PlacedVehicle("b", 1412.5, VehicleRole.BUNCHED)),
                List.of(bunch));
        // A direction with too few vehicles has no expected spacing, which must survive as null.
        DirectionSpacing sparse = new DirectionSpacing(
                (short) 1, 17_400.0, false, null, SpacingBasis.OBSERVED,
                List.of(new PlacedVehicle("c", 5000.0, VehicleRole.SPACED)), List.of());
        return new SpacingSnapshot(FEED, FEED.plusSeconds(3), List.of(new RouteSpacing("501", List.of(direction, sparse))));
    }

    @Test
    void nothingStoredReadsAsNoSpacingRatherThanAnEmptyNetwork() {
        SpacingRead read = store.readSpacing();

        assertThat(read.snapshot()).isNull();
        assertThat(read.source()).isEqualTo(DataSource.NO_DATA);
    }

    @Test
    void publishedSpacingComesBackFromRedisUnchangedIncludingItsBunches() {
        SpacingSnapshot published = spacing();

        store.publishSpacing(published);
        SpacingRead read = store.readSpacing();

        assertThat(read.source()).isEqualTo(DataSource.LIVE);
        assertThat(read.degraded()).isFalse();
        assertThat(read.snapshot()).isEqualTo(published);
        assertThat(read.snapshot().bunches()).singleElement()
                .satisfies(bunch -> assertThat(bunch.severity()).isEqualTo(BunchingSeverity.SEVERE));
    }

    @Test
    void anAbsentExpectedSpacingStaysAbsentAndIsNotTurnedIntoZero() {
        store.publishSpacing(spacing());

        DirectionSpacing sparse = store.readSpacing().snapshot().routes().getFirst().directions().get(1);

        assertThat(sparse.expectedGapMetres()).isNull();
    }

    @Test
    void spacingGetsAnExpiryLikeEverythingInRedis() {
        store.publishSpacing(spacing());

        Long seconds = redis.getExpire(RouteHealthStore.SPACING_KEY);

        assertThat(seconds).isNotNull().isPositive();
        assertThat(Duration.ofSeconds(seconds)).isLessThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void spacingAndVerdictsAreSeparateDocumentsSoTheVerdictListDoesNotCarryEveryVehicle() {
        store.publish(snapshot());
        store.publishSpacing(spacing());

        assertThat(redis.opsForValue().get(RouteHealthStore.KEY)).doesNotContain("progressMetres");
        assertThat(redis.opsForValue().get(RouteHealthStore.SPACING_KEY)).contains("progressMetres");
    }

    @Test
    void whenRedisHasLostTheSpacingThisProcessesOwnCopyAnswersAndIsMarkedDegraded() {
        SpacingSnapshot published = spacing();
        store.publishSpacing(published);
        redis.delete(RouteHealthStore.SPACING_KEY);

        SpacingRead read = store.readSpacing();

        assertThat(read.snapshot()).isEqualTo(published);
        assertThat(read.source()).isEqualTo(DataSource.CACHED);
        assertThat(read.degraded()).isTrue();
    }

    @Test
    void anUnreadableSpacingDocumentFallsBackInsteadOfFailingTheRequest() {
        SpacingSnapshot published = spacing();
        store.publishSpacing(published);
        redis.opsForValue().set(RouteHealthStore.SPACING_KEY, "{ not a snapshot");

        SpacingRead read = store.readSpacing();

        assertThat(read.snapshot()).isEqualTo(published);
        assertThat(read.source()).isEqualTo(DataSource.CACHED);
    }
}
