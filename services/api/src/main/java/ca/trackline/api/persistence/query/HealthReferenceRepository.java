package ca.trackline.api.persistence.query;

import java.util.HashMap;
import java.util.Map;

import ca.trackline.api.gtfs.schedule.derive.ScheduledHeadwayBuilder.DayType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The schedule-side facts the route-health pass compares live vehicles against.
 *
 * <p>Both queries are small by construction, 470 primary shapes and at most that many
 * scheduled headways for one hour, so they are read fresh each cycle. That keeps an
 * import taking effect at once and avoids a cache that could outlive the data it holds.
 */
@Repository
public class HealthReferenceRepository {

    private final JdbcTemplate jdbc;

    public HealthReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A route and one of its two directions. */
    public record DirectionKey(String routeId, short directionId) {
    }

    /**
     * @param lengthMetres the shape's length, which is how far a vehicle can be along it
     * @param loop         whether the route returns to its start
     */
    public record ShapeInfo(double lengthMetres, boolean loop) {
    }

    /** The primary shape of every route direction: the line live vehicles are projected onto. */
    public Map<DirectionKey, ShapeInfo> primaryShapes() {
        Map<DirectionKey, ShapeInfo> shapes = new HashMap<>();
        jdbc.query(
                "SELECT route_id, direction_id, length_m, is_loop FROM route_shapes WHERE is_primary",
                rows -> {
                    shapes.put(
                            new DirectionKey(rows.getString("route_id"), rows.getShort("direction_id")),
                            new ShapeInfo(rows.getDouble("length_m"), rows.getBoolean("is_loop")));
                });
        return shapes;
    }

    /**
     * The timetable's median spacing between departures, in seconds, for one day type and hour.
     *
     * <p>A route direction with no row is absent from the map, which means the timetable has
     * no spacing to compare against (a single departure in the hour has no gap) and not that
     * the spacing is zero.
     */
    public Map<DirectionKey, Double> scheduledHeadwaySeconds(DayType dayType, int hourOfDay) {
        Map<DirectionKey, Double> headways = new HashMap<>();
        jdbc.query(
                """
                SELECT route_id, direction_id, median_headway_seconds
                FROM route_scheduled_headways
                WHERE day_type = ? AND hour_of_day = ?
                """,
                rows -> {
                    headways.put(
                            new DirectionKey(rows.getString("route_id"), rows.getShort("direction_id")),
                            rows.getDouble("median_headway_seconds"));
                },
                dayType.name(),
                hourOfDay);
        return headways;
    }
}
