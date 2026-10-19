package ca.trackline.api.persistence.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.history.ReliabilityModels.HourlyRollup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the hourly summaries a reliability figure is built from.
 */
@Repository
public class ReliabilityQueryRepository {

    private final JdbcTemplate jdbc;

    public ReliabilityQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every hourly row for a route that starts at or after a time. At most a few thousand rows even for the
     * longest window, two directions for each hour, so the grouping is done by the caller in plain code
     * that can be tested without a database.
     */
    public List<HourlyRollup> findForRoute(String routeId, Instant from) {
        return jdbc.query(
                """
                SELECT direction_id, hour_start, samples, excellent, normal, unstable, major_disruption, unknown,
                       variation_sum, variation_samples, gap_ratio_sum, gap_ratio_samples, bunching_samples
                FROM route_headway_hourly
                WHERE route_id = ? AND hour_start >= ?
                ORDER BY hour_start, direction_id
                """,
                (rows, index) -> new HourlyRollup(
                        routeId,
                        rows.getShort("direction_id"),
                        rows.getTimestamp("hour_start").toInstant(),
                        rows.getInt("samples"),
                        rows.getInt("excellent"),
                        rows.getInt("normal"),
                        rows.getInt("unstable"),
                        rows.getInt("major_disruption"),
                        rows.getInt("unknown"),
                        rows.getDouble("variation_sum"),
                        rows.getInt("variation_samples"),
                        rows.getDouble("gap_ratio_sum"),
                        rows.getInt("gap_ratio_samples"),
                        rows.getInt("bunching_samples")),
                routeId, Timestamp.from(from));
    }
}
