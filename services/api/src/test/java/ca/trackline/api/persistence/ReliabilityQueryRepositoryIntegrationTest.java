package ca.trackline.api.persistence;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.history.ReliabilityModels.HourlyRollup;
import ca.trackline.api.persistence.query.ReliabilityQueryRepository;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Reading the hourly summaries back: one route, from a time, in order, with every column mapped.
 */
class ReliabilityQueryRepositoryIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private ReliabilityQueryRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.execute("TRUNCATE route_headway_hourly");
    }

    private void row(String route, int direction, String hourStart, int excellent, int unknown) {
        jdbc.update(
                """
                INSERT INTO route_headway_hourly (
                    route_id, direction_id, hour_start, samples, excellent, normal, unstable, major_disruption, unknown,
                    variation_sum, variation_samples, gap_ratio_sum, gap_ratio_samples, bunching_samples,
                    first_sample_at, last_sample_at)
                VALUES (?, ?, ?::timestamptz, ?, ?, 0, 0, 0, ?, 1.5, 5, 7.25, 5, 2, ?::timestamptz, ?::timestamptz)
                """,
                route, direction, hourStart, excellent + unknown, excellent, unknown, hourStart, hourStart);
    }

    @Test
    void returnsOnlyTheRoutesRowsFromTheStartTimeOnwardsInOrder() {
        row("501", 1, "2026-10-13T10:00:00Z", 10, 0);
        row("501", 0, "2026-10-13T10:00:00Z", 10, 0);
        row("501", 0, "2026-10-13T09:00:00Z", 10, 0);
        row("501", 0, "2026-10-13T08:00:00Z", 10, 0);
        row("504", 0, "2026-10-13T10:00:00Z", 10, 0);

        List<HourlyRollup> rows = repository.findForRoute("501", Instant.parse("2026-10-13T09:00:00Z"));

        // The 08:00 hour is before the start; the start itself is included; another route's row is not.
        assertThat(rows).extracting(HourlyRollup::hourStart, HourlyRollup::directionId).containsExactly(
                org.assertj.core.groups.Tuple.tuple(Instant.parse("2026-10-13T09:00:00Z"), (short) 0),
                org.assertj.core.groups.Tuple.tuple(Instant.parse("2026-10-13T10:00:00Z"), (short) 0),
                org.assertj.core.groups.Tuple.tuple(Instant.parse("2026-10-13T10:00:00Z"), (short) 1));
    }

    @Test
    void everyColumnIsMappedToTheRightField() {
        row("501", 0, "2026-10-13T10:00:00Z", 8, 2);

        HourlyRollup rollup = repository.findForRoute("501", Instant.parse("2026-10-13T00:00:00Z")).getFirst();

        assertThat(rollup.routeId()).isEqualTo("501");
        assertThat(rollup.samples()).isEqualTo(10);
        assertThat(rollup.excellent()).isEqualTo(8);
        assertThat(rollup.unknown()).isEqualTo(2);
        assertThat(rollup.variationSum()).isCloseTo(1.5, within(1e-9));
        assertThat(rollup.variationSamples()).isEqualTo(5);
        assertThat(rollup.gapRatioSum()).isCloseTo(7.25, within(1e-9));
        assertThat(rollup.gapRatioSamples()).isEqualTo(5);
        assertThat(rollup.bunchingSamples()).isEqualTo(2);
    }

    @Test
    void aRouteWithNothingStoredGetsAnEmptyList() {
        assertThat(repository.findForRoute("none", Instant.EPOCH)).isEmpty();
    }
}
