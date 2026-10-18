package ca.trackline.api.persistence.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingDtos.BunchingEventView;
import ca.trackline.api.analytics.bunching.BunchingDtos.EventStatus;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read queries over the stored bunching events.
 */
@Repository
public class BunchingQueryRepository {

    private final JdbcTemplate jdbc;

    public BunchingQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The bunches open on a route and those that resolved since a given time, newest first.
     *
     * <p>Open ones are never cut off by age: a bunch that has lasted an hour is exactly the one a
     * reader wants to see. The limit bounds the resolved history, which grows without end.
     */
    public List<BunchingEventView> findForRoute(String routeId, Instant resolvedSince, int limit) {
        return jdbc.query(
                """
                SELECT id, direction_id, vehicle_ids, peak_vehicles, span_m, expected_gap_m, service_gap_m,
                       basis, severity, peak_severity, sightings, first_seen_at, last_seen_at, resolved_at
                FROM bunching_events
                WHERE route_id = ? AND (resolved_at IS NULL OR resolved_at >= ?)
                ORDER BY first_seen_at DESC, id DESC
                LIMIT ?
                """,
                (rows, index) -> {
                    Timestamp resolved = rows.getTimestamp("resolved_at");
                    return new BunchingEventView(
                            rows.getLong("id"),
                            rows.getShort("direction_id"),
                            resolved == null ? EventStatus.ACTIVE : EventStatus.RESOLVED,
                            List.of((String[]) rows.getArray("vehicle_ids").getArray()),
                            rows.getInt("peak_vehicles"),
                            round(rows.getDouble("span_m")),
                            round(rows.getDouble("expected_gap_m")),
                            round(rows.getDouble("service_gap_m")),
                            SpacingBasis.valueOf(rows.getString("basis")),
                            BunchingSeverity.valueOf(rows.getString("severity")),
                            BunchingSeverity.valueOf(rows.getString("peak_severity")),
                            rows.getInt("sightings"),
                            rows.getTimestamp("first_seen_at").toInstant(),
                            rows.getTimestamp("last_seen_at").toInstant(),
                            resolved == null ? null : resolved.toInstant());
                },
                routeId, Timestamp.from(resolvedSince), limit);
    }

    /** The columns are REAL, so a value comes back as the nearest float; rounded to a tenth for reading. */
    private static double round(double metres) {
        return Math.round(metres * 10) / 10.0;
    }
}
