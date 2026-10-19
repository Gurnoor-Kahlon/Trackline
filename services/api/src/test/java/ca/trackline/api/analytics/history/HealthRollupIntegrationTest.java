package ca.trackline.api.analytics.history;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import ca.trackline.api.persistence.partition.DailyPartitions;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The hourly summary, against a real database and sums worked out by hand.
 */
class HealthRollupIntegrationTest extends PostgisIntegrationTest {

    private static final String ROUTE = "ROLL";

    @Autowired
    private HealthRollup rollup;

    @Autowired
    private DailyPartitions partitions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // The summary starts from the latest hour already summarised, across every route, so a row left by another
        // test would make it skip these.
        jdbc.execute("TRUNCATE route_health_samples, route_headway_hourly");
        partitions.ensure(HealthSampler.TABLE, LocalDate.of(2026, 3, 10));
        partitions.ensure(HealthSampler.TABLE, LocalDate.of(2026, 3, 11));
    }

    @AfterEach
    void tidy() {
        jdbc.execute("TRUNCATE route_health_samples, route_headway_hourly");
    }

    private void sample(String route, int direction, String at, String state, Double variation, Double gapRatio, int clusters) {
        jdbc.update(
                """
                INSERT INTO route_health_samples (
                    route_id, direction_id, sampled_at, state, confidence, reason_codes, vehicles,
                    variation, gap_ratio, bunching_clusters)
                VALUES (?, ?, ?::timestamptz, ?, 'HIGH', '{}', 5, ?, ?, ?)
                """,
                route, direction, at, state, variation, gapRatio, clusters);
    }

    private Map<String, Object> hourly(String route, int direction, String hourStart) {
        return jdbc.queryForMap(
                "SELECT * FROM route_headway_hourly WHERE route_id = ? AND direction_id = ? AND hour_start = ?::timestamptz",
                route, direction, hourStart);
    }

    private int hourlyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM route_headway_hourly", Integer.class);
    }

    @Test
    void anHoursSamplesAreSummarisedAsCountsAndSums() {
        // Six samples in the 10:00 UTC hour with every verdict, a missing figure and two with a bunch.
        sample(ROUTE, 0, "2026-03-10T10:00:00Z", "EXCELLENT", 0.2, 1.0, 0);
        sample(ROUTE, 0, "2026-03-10T10:01:00Z", "EXCELLENT", 0.4, 1.1, 0);
        sample(ROUTE, 0, "2026-03-10T10:02:00Z", "NORMAL", 0.5, 1.5, 0);
        sample(ROUTE, 0, "2026-03-10T10:03:00Z", "UNSTABLE", 0.9, 2.0, 1);
        sample(ROUTE, 0, "2026-03-10T10:04:00Z", "MAJOR_DISRUPTION", 1.0, 3.0, 2);
        sample(ROUTE, 0, "2026-03-10T10:59:00Z", "UNKNOWN", null, null, 0);

        int written = rollup.rollUp(Instant.parse("2026-03-10T11:00:00Z"));

        assertThat(written).isEqualTo(1);
        Map<String, Object> row = hourly(ROUTE, 0, "2026-03-10T10:00:00Z");
        assertThat(row.get("samples")).isEqualTo(6);
        assertThat(row.get("excellent")).isEqualTo(2);
        assertThat(row.get("normal")).isEqualTo(1);
        assertThat(row.get("unstable")).isEqualTo(1);
        assertThat(row.get("major_disruption")).isEqualTo(1);
        assertThat(row.get("unknown")).isEqualTo(1);
        // Five samples had a figure; the unknown one had none and is neither a zero in the sum nor in the count.
        assertThat((Double) row.get("variation_sum")).isCloseTo(3.0, within(1e-6));
        assertThat(row.get("variation_samples")).isEqualTo(5);
        assertThat((Double) row.get("gap_ratio_sum")).isCloseTo(8.6, within(1e-6));
        assertThat(row.get("gap_ratio_samples")).isEqualTo(5);
        assertThat(row.get("bunching_samples")).isEqualTo(2);
        assertThat(((Timestamp) row.get("first_sample_at")).toInstant()).isEqualTo(Instant.parse("2026-03-10T10:00:00Z"));
        assertThat(((Timestamp) row.get("last_sample_at")).toInstant()).isEqualTo(Instant.parse("2026-03-10T10:59:00Z"));
    }

    @Test
    void eachDirectionAndEachHourIsItsOwnRow() {
        sample(ROUTE, 0, "2026-03-10T10:10:00Z", "NORMAL", 0.3, 1.2, 0);
        sample(ROUTE, 1, "2026-03-10T10:10:00Z", "UNSTABLE", 0.6, 2.0, 0);
        sample(ROUTE, 0, "2026-03-10T11:10:00Z", "NORMAL", 0.3, 1.2, 0);
        sample("ROLL2", 0, "2026-03-10T10:10:00Z", "EXCELLENT", 0.1, 1.0, 0);

        rollup.rollUp(Instant.parse("2026-03-10T13:00:00Z"));

        assertThat(hourlyRows()).isEqualTo(4);
        assertThat(hourly(ROUTE, 0, "2026-03-10T10:00:00Z").get("normal")).isEqualTo(1);
        assertThat(hourly(ROUTE, 1, "2026-03-10T10:00:00Z").get("unstable")).isEqualTo(1);
        assertThat(hourly("ROLL2", 0, "2026-03-10T10:00:00Z").get("excellent")).isEqualTo(1);
    }

    @Test
    void anHourThatHasNotFinishedIsNotSummarisedYet() {
        sample(ROUTE, 0, "2026-03-10T11:10:00Z", "NORMAL", 0.3, 1.2, 0);

        // Still inside the 11:00 hour: a summary now would be mistaken for the whole of it.
        assertThat(rollup.rollUp(Instant.parse("2026-03-10T11:45:00Z"))).isZero();
        assertThat(hourlyRows()).isZero();

        // Exactly when the hour ends it is complete.
        assertThat(rollup.rollUp(Instant.parse("2026-03-10T12:00:00Z"))).isEqualTo(1);
        assertThat(hourlyRows()).isEqualTo(1);
    }

    @Test
    void summarisingAgainChangesNothing() {
        sample(ROUTE, 0, "2026-03-10T10:10:00Z", "NORMAL", 0.3, 1.2, 0);
        sample(ROUTE, 0, "2026-03-10T10:11:00Z", "UNSTABLE", 0.6, 2.0, 1);
        Instant now = Instant.parse("2026-03-10T12:00:00Z");

        rollup.rollUp(now);
        Map<String, Object> first = hourly(ROUTE, 0, "2026-03-10T10:00:00Z");
        rollup.rollUp(now);
        rollup.rollUp(now);

        assertThat(hourlyRows()).isEqualTo(1);
        assertThat(hourly(ROUTE, 0, "2026-03-10T10:00:00Z")).isEqualTo(first);
    }

    @Test
    void theLatestHourAlreadySummarisedIsRebuiltSoALateSampleIsNotLost() {
        sample(ROUTE, 0, "2026-03-10T10:10:00Z", "NORMAL", 0.3, 1.2, 0);
        rollup.rollUp(Instant.parse("2026-03-10T11:00:05Z"));
        assertThat(hourly(ROUTE, 0, "2026-03-10T10:00:00Z").get("samples")).isEqualTo(1);

        // A sample written just after the hour was summarised, as the cycle that produced it was still finishing.
        sample(ROUTE, 0, "2026-03-10T10:59:59Z", "UNSTABLE", 0.6, 2.0, 0);
        rollup.rollUp(Instant.parse("2026-03-10T11:10:00Z"));

        Map<String, Object> row = hourly(ROUTE, 0, "2026-03-10T10:00:00Z");
        assertThat(row.get("samples")).isEqualTo(2);
        assertThat(row.get("unstable")).isEqualTo(1);
    }

    @Test
    void laterHoursAreAddedAsTheyFinishWithoutDisturbingEarlierOnes() {
        sample(ROUTE, 0, "2026-03-10T10:10:00Z", "NORMAL", 0.3, 1.2, 0);
        rollup.rollUp(Instant.parse("2026-03-10T11:00:00Z"));
        sample(ROUTE, 0, "2026-03-10T11:10:00Z", "EXCELLENT", 0.1, 1.0, 0);

        rollup.rollUp(Instant.parse("2026-03-10T12:00:00Z"));

        assertThat(hourlyRows()).isEqualTo(2);
        assertThat(hourly(ROUTE, 0, "2026-03-10T10:00:00Z").get("normal")).isEqualTo(1);
        assertThat(hourly(ROUTE, 0, "2026-03-10T11:00:00Z").get("excellent")).isEqualTo(1);
    }

    @Test
    void anHourBoundaryIsAUtcBoundary() {
        sample(ROUTE, 0, "2026-03-10T09:59:59.999Z", "NORMAL", 0.3, 1.2, 0);
        sample(ROUTE, 0, "2026-03-10T10:00:00Z", "NORMAL", 0.3, 1.2, 0);

        rollup.rollUp(Instant.parse("2026-03-10T12:00:00Z"));

        assertThat(hourly(ROUTE, 0, "2026-03-10T09:00:00Z").get("samples")).isEqualTo(1);
        assertThat(hourly(ROUTE, 0, "2026-03-10T10:00:00Z").get("samples")).isEqualTo(1);
    }

    @Test
    void withNoSamplesThereIsNothingToSummariseAndNothingGoesWrong() {
        assertThat(rollup.rollUp(Instant.parse("2026-03-10T12:00:00Z"))).isZero();
        assertThat(jdbc.queryForList("SELECT 1 FROM route_headway_hourly")).isEmpty();
    }

    @Test
    void theSummaryIsExactlyWhatTheSamplesAddUpTo() {
        // A cross-check by an independent route: every sample is in exactly one hourly row, whatever the grouping.
        for (int minute = 0; minute < 50; minute++) {
            String state = List.of("EXCELLENT", "NORMAL", "UNSTABLE", "UNKNOWN").get(minute % 4);
            sample(ROUTE, minute % 2, "2026-03-10T10:%02d:00Z".formatted(minute), state, "UNKNOWN".equals(state) ? null : 0.3, null, 0);
        }

        rollup.rollUp(Instant.parse("2026-03-10T12:00:00Z"));

        assertThat(jdbc.queryForObject("SELECT sum(samples) FROM route_headway_hourly", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT sum(unknown) FROM route_headway_hourly", Integer.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM route_health_samples WHERE state = 'UNKNOWN'", Integer.class));
    }
}
