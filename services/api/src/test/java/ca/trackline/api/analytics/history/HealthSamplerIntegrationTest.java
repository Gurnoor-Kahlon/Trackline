package ca.trackline.api.analytics.history;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import ca.trackline.api.analytics.health.HealthConfidence;
import ca.trackline.api.analytics.health.ReasonCode;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.health.RouteHealthState;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import ca.trackline.api.persistence.partition.DailyPartitions;
import ca.trackline.api.persistence.partition.DailyPartitions.Partition;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * What is recorded from an analysis, and when.
 */
class HealthSamplerIntegrationTest extends PostgisIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-03-10T10:00:00Z");

    @Autowired
    private HealthSampler sampler;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private DailyPartitions partitions;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE route_health_samples");
        sampler.forget();
    }

    @AfterEach
    void tidy() {
        Mockito.reset(partitions);
        jdbc.execute("TRUNCATE route_health_samples");
    }

    private static HealthMetrics metrics(
            int vehicles, Double variation, Double gapRatio, Double supply, int clusters, SpacingBasis basis) {
        return new HealthMetrics(vehicles, Math.max(0, vehicles - 1), null, null, variation, gapRatio, null, null, supply,
                null, null, 0, 0, basis, clusters);
    }

    private static DirectionHealth direction(
            int id, RouteHealthState state, HealthConfidence confidence, List<ReasonCode> reasons, HealthMetrics metrics) {
        return new DirectionHealth((short) id, state, confidence, reasons, metrics);
    }

    private static RouteHealth route(String id, DirectionHealth... directions) {
        return new RouteHealth(id, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), List.of(directions), 0);
    }

    private static HealthSnapshot snapshot(RouteHealth... routes) {
        return new HealthSnapshot(T0, T0, true, List.of(routes));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM route_health_samples", Integer.class);
    }

    @Test
    void oneRowIsWrittenForEachDirectionOfEachRouteWithTheFiguresBehindTheVerdict() {
        HealthSnapshot snapshot = snapshot(
                route("SAMP-1",
                        direction(0, RouteHealthState.UNSTABLE, HealthConfidence.HIGH,
                                List.of(ReasonCode.LARGE_GAP, ReasonCode.BUNCHING),
                                metrics(6, 0.62, 2.1, 0.9, 1, SpacingBasis.SCHEDULE)),
                        direction(1, RouteHealthState.EXCELLENT, HealthConfidence.MEDIUM,
                                List.of(ReasonCode.NO_SCHEDULE_COVERAGE),
                                metrics(5, 0.1, 1.1, null, 0, SpacingBasis.OBSERVED))),
                route("SAMP-2",
                        direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(),
                                metrics(4, 0.3, 1.4, 1.0, 0, SpacingBasis.SCHEDULE))));

        int written = sampler.sample(snapshot, T0);

        assertThat(written).isEqualTo(3);
        assertThat(rows()).isEqualTo(3);
        Map<String, Object> unstable = jdbc.queryForMap(
                "SELECT * FROM route_health_samples WHERE route_id = 'SAMP-1' AND direction_id = 0");
        assertThat(unstable.get("state")).isEqualTo("UNSTABLE");
        assertThat(unstable.get("confidence")).isEqualTo("HIGH");
        assertThat(((java.sql.Array) unstable.get("reason_codes")).toString()).isEqualTo("{LARGE_GAP,BUNCHING}");
        assertThat(unstable.get("vehicles")).isEqualTo(6);
        assertThat(((Number) unstable.get("variation")).doubleValue()).isCloseTo(0.62, within(1e-6));
        assertThat(((Number) unstable.get("gap_ratio")).doubleValue()).isCloseTo(2.1, within(1e-6));
        assertThat(((Number) unstable.get("supply_ratio")).doubleValue()).isCloseTo(0.9, within(1e-6));
        assertThat(unstable.get("bunching_clusters")).isEqualTo(1);
        assertThat(unstable.get("basis")).isEqualTo("SCHEDULE");
        assertThat(((java.sql.Timestamp) unstable.get("sampled_at")).toInstant()).isEqualTo(T0);

        // A figure that could not be computed is stored as null and not as zero.
        Map<String, Object> noSupply = jdbc.queryForMap(
                "SELECT * FROM route_health_samples WHERE route_id = 'SAMP-1' AND direction_id = 1");
        assertThat(noSupply.get("supply_ratio")).isNull();
        assertThat(noSupply.get("basis")).isEqualTo("OBSERVED");
    }

    @Test
    void aDirectionThatCouldNotBeJudgedIsRecordedAsUnknownSoTheTimeNotJudgedIsMeasured() {
        sampler.sample(snapshot(route("SAMP-1",
                direction(0, RouteHealthState.UNKNOWN, HealthConfidence.NONE, List.of(ReasonCode.INSUFFICIENT_VEHICLES),
                        metrics(1, null, null, null, 0, null)))), T0);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM route_health_samples");
        assertThat(row.get("state")).isEqualTo("UNKNOWN");
        assertThat(row.get("variation")).isNull();
        assertThat(row.get("gap_ratio")).isNull();
        assertThat(row.get("basis")).isNull();
        assertThat(((java.sql.Array) row.get("reason_codes")).toString()).isEqualTo("{INSUFFICIENT_VEHICLES}");
    }

    @Test
    void aVerdictWithNoReasonsIsStoredWithAnEmptyArrayNotANull() {
        sampler.sample(snapshot(route("SAMP-1",
                direction(0, RouteHealthState.EXCELLENT, HealthConfidence.HIGH, List.of(),
                        metrics(6, 0.1, 1.0, 1.0, 0, SpacingBasis.SCHEDULE)))), T0);

        assertThat(jdbc.queryForObject("SELECT cardinality(reason_codes) FROM route_health_samples", Integer.class)).isZero();
    }

    @Test
    void anotherSampleBeforeTheIntervalHasPassedWritesNothing() {
        HealthSnapshot snapshot = snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE))));

        assertThat(sampler.sample(snapshot, T0)).isEqualTo(1);
        // The analysis runs every cycle, about every twenty seconds.
        assertThat(sampler.sample(snapshot, T0.plusSeconds(20))).isZero();
        assertThat(sampler.sample(snapshot, T0.plusSeconds(59))).isZero();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void theNextSampleIsTakenWhenTheIntervalHasExactlyPassed() {
        HealthSnapshot snapshot = snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE))));
        sampler.sample(snapshot, T0);

        assertThat(sampler.sample(snapshot, T0.plus(Duration.ofSeconds(60)))).isEqualTo(1);
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void theIntervalIsMeasuredFromTheLastSampleTakenNotFromTheLastAttempt() {
        HealthSnapshot snapshot = snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE))));
        sampler.sample(snapshot, T0);
        sampler.sample(snapshot, T0.plusSeconds(40));

        // Forty seconds in was skipped, so it must not have pushed the next one out to a hundred.
        assertThat(sampler.sample(snapshot, T0.plusSeconds(60))).isEqualTo(1);
    }

    @Test
    void anAnalysisWithNothingToRecordWritesNothingAndDoesNotStartTheInterval() {
        assertThat(sampler.sample(snapshot(), T0)).isZero();
        assertThat(sampler.sample(snapshot(new RouteHealth(
                "SAMP-1", RouteHealthState.UNKNOWN, HealthConfidence.NONE, List.of(ReasonCode.INSUFFICIENT_VEHICLES), List.of(), 1)), T0))
                .isZero();

        // A route whose vehicles could not be placed has no direction to record, and a later real sample is not held back.
        assertThat(sampler.sample(snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE)))),
                T0.plusSeconds(1))).isEqualTo(1);
    }

    @Test
    void samplingTheSameInstantTwiceCannotDuplicateARow() {
        HealthSnapshot snapshot = snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE))));
        sampler.sample(snapshot, T0);

        // A restart forgets when the last one was; the key makes a repeat harmless.
        sampler.forget();
        assertThat(sampler.sample(snapshot, T0)).isZero();

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void thePartitionForTodayAndTomorrowIsCreatedWhenNeeded() {
        sampler.sample(snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE)))),
                Instant.parse("2026-03-10T23:59:30Z"));

        List<String> names = partitions.list(HealthSampler.TABLE).stream().map(Partition::name).toList();
        assertThat(names).contains("route_health_samples_2026_03_10", "route_health_samples_2026_03_11");
    }

    @Test
    void aWriteThatFailsDoesNotStartTheIntervalSoTheNextCycleTriesAgain() {
        HealthSnapshot snapshot = snapshot(route("SAMP-1",
                direction(0, RouteHealthState.NORMAL, HealthConfidence.HIGH, List.of(), metrics(5, 0.3, 1.2, 1.0, 0, SpacingBasis.SCHEDULE))));
        Mockito.doThrow(new IllegalStateException("no partition for you"))
                .when(partitions).ensure(Mockito.eq(HealthSampler.TABLE), Mockito.any(LocalDate.class));

        assertThatThrownBy(() -> sampler.sample(snapshot, T0)).isInstanceOf(IllegalStateException.class);
        Mockito.reset(partitions);

        // Twenty seconds later, well inside the interval, it is tried again because nothing was recorded.
        assertThat(sampler.sample(snapshot, T0.plusSeconds(20))).isEqualTo(1);
    }
}
