package ca.trackline.api.analytics.history;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Summarises finished hours of route health samples into {@code route_headway_hourly}.
 *
 * <p>Idempotent: an hour is rebuilt from its samples every time it is summarised, so running twice, or
 * running again after an interruption, gives the same rows. It starts from the latest hour already
 * summarised and not the one after, so an hour that was summarised just as samples were still being
 * written is corrected by the next run.
 *
 * <p>Only finished hours are summarised. The current hour is incomplete, and a summary of it would be
 * mistaken for a whole one.
 *
 * <p>The summary holds counts and sums, not averages. The mean over any window is then the sum of the sums
 * over the sum of the counts, exact for the samples taken; an average of hourly averages would weight an
 * hour with a few samples like one with sixty.
 */
@Service
public class HealthRollup {

    private static final String ROWS = "trackline.history.rollup.rows";

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public HealthRollup(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    /**
     * @param now the current time; hours that have not finished by then are left alone
     * @return how many hourly rows were written or rewritten
     */
    public int rollUp(Instant now) {
        Instant through = now.truncatedTo(ChronoUnit.HOURS);
        Timestamp from = jdbc.queryForObject("SELECT max(hour_start) FROM route_headway_hourly", Timestamp.class);

        int rows = jdbc.update(
                """
                INSERT INTO route_headway_hourly (
                    route_id, direction_id, hour_start, samples,
                    excellent, normal, unstable, major_disruption, unknown,
                    variation_sum, variation_samples, gap_ratio_sum, gap_ratio_samples,
                    bunching_samples, first_sample_at, last_sample_at)
                SELECT route_id, direction_id, date_trunc('hour', sampled_at, 'UTC'), count(*),
                       count(*) FILTER (WHERE state = 'EXCELLENT'),
                       count(*) FILTER (WHERE state = 'NORMAL'),
                       count(*) FILTER (WHERE state = 'UNSTABLE'),
                       count(*) FILTER (WHERE state = 'MAJOR_DISRUPTION'),
                       count(*) FILTER (WHERE state = 'UNKNOWN'),
                       COALESCE(sum(variation), 0), count(variation),
                       COALESCE(sum(gap_ratio), 0), count(gap_ratio),
                       count(*) FILTER (WHERE bunching_clusters > 0),
                       min(sampled_at), max(sampled_at)
                FROM route_health_samples
                WHERE sampled_at >= COALESCE(?, '-infinity'::timestamptz) AND sampled_at < ?
                GROUP BY route_id, direction_id, date_trunc('hour', sampled_at, 'UTC')
                ON CONFLICT (route_id, direction_id, hour_start) DO UPDATE SET
                    samples = EXCLUDED.samples,
                    excellent = EXCLUDED.excellent,
                    normal = EXCLUDED.normal,
                    unstable = EXCLUDED.unstable,
                    major_disruption = EXCLUDED.major_disruption,
                    unknown = EXCLUDED.unknown,
                    variation_sum = EXCLUDED.variation_sum,
                    variation_samples = EXCLUDED.variation_samples,
                    gap_ratio_sum = EXCLUDED.gap_ratio_sum,
                    gap_ratio_samples = EXCLUDED.gap_ratio_samples,
                    bunching_samples = EXCLUDED.bunching_samples,
                    first_sample_at = EXCLUDED.first_sample_at,
                    last_sample_at = EXCLUDED.last_sample_at
                """,
                from, Timestamp.from(through));

        registry.counter(ROWS).increment(rows);
        return rows;
    }
}
