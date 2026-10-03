package ca.trackline.api.persistence.query;

import java.util.List;
import java.util.Optional;

import ca.trackline.api.routes.RouteDtos.RouteDirection;
import ca.trackline.api.routes.RouteDtos.RouteStop;
import ca.trackline.api.routes.RouteDtos.RouteSummary;
import ca.trackline.api.routes.TransitMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read queries for routes.
 *
 * <p>Written as SQL projections rather than entity graphs. Every one of these
 * answers a specific question with a specific shape, and loading entities to
 * discard most of their fields would cost extra joins and invite the lazy-loading
 * problems {@code open-in-view: false} exists to prevent.
 */
@Repository
public class RouteQueryRepository {

    private static final String SUMMARY_COLUMNS =
            "route_id, short_name, long_name, route_type, color, text_color, supports_realtime";

    /**
     * Ordered so riders see numbers in the order they expect: 1, 7, 41, 501 rather
     * than 1, 101, 41. Route ids are text in GTFS and sort as text unless told
     * otherwise.
     */
    private static final String ROUTE_ORDER =
            "ORDER BY CASE WHEN short_name ~ '^[0-9]+$' THEN 0 ELSE 1 END, "
                    + "CASE WHEN short_name ~ '^[0-9]+$' THEN short_name::int END, short_name";

    private final JdbcTemplate jdbc;

    public RouteQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long countRoutes(TransitMode mode, String nameFilter) {
        Long total = jdbc.queryForObject(
                """
                SELECT count(*) FROM routes
                WHERE (?::int IS NULL OR route_type = ?::int)
                  AND (?::text IS NULL OR (short_name || ' ' || long_name) ILIKE '%' || ?::text || '%')
                """,
                Long.class,
                routeType(mode), routeType(mode), nameFilter, nameFilter);
        return total == null ? 0 : total;
    }

    public List<RouteSummary> findRoutes(TransitMode mode, String nameFilter, int page, int size) {
        return jdbc.query(
                """
                SELECT %s FROM routes
                WHERE (?::int IS NULL OR route_type = ?::int)
                  AND (?::text IS NULL OR (short_name || ' ' || long_name) ILIKE '%%' || ?::text || '%%')
                %s
                LIMIT ? OFFSET ?
                """.formatted(SUMMARY_COLUMNS, ROUTE_ORDER),
                summaryMapper(),
                routeType(mode), routeType(mode), nameFilter, nameFilter, size, (long) page * size);
    }

    public Optional<RouteSummary> findRoute(String routeId) {
        return jdbc
                .query("SELECT %s FROM routes WHERE route_id = ?".formatted(SUMMARY_COLUMNS), summaryMapper(), routeId)
                .stream()
                .findFirst();
    }

    /**
     * One row per direction the schedule serves.
     *
     * <p>The headsign is the one the most trips show, because a direction usually
     * has several — short turns and branches — and the dominant one is what riders
     * recognise. Shape facts come from the primary shape, the same geometry live
     * vehicles will be projected onto.
     */
    public List<RouteDirection> findDirections(String routeId) {
        return jdbc.query(
                """
                WITH trips_by_direction AS (
                    SELECT direction_id, count(*)::int AS trip_count
                    FROM trips WHERE route_id = ?
                    GROUP BY direction_id
                ),
                dominant_headsign AS (
                    SELECT DISTINCT ON (direction_id) direction_id, headsign
                    FROM (
                        SELECT direction_id, headsign, count(*) AS uses
                        FROM trips WHERE route_id = ? AND headsign IS NOT NULL
                        GROUP BY direction_id, headsign) ranked
                    ORDER BY direction_id, uses DESC, headsign
                ),
                primary_shape AS (
                    SELECT direction_id, length_m, is_loop
                    FROM route_shapes WHERE route_id = ? AND is_primary
                ),
                pattern_size AS (
                    SELECT direction_id, max(stop_count)::int AS stop_count
                    FROM (
                        SELECT t.direction_id, count(*) AS stop_count
                        FROM trips t JOIN stop_times st ON st.trip_id = t.trip_id
                        WHERE t.route_id = ?
                        GROUP BY t.trip_id, t.direction_id) per_trip
                    GROUP BY direction_id
                )
                SELECT d.direction_id, h.headsign, d.trip_count, p.stop_count,
                       s.length_m, coalesce(s.is_loop, FALSE) AS is_loop
                FROM trips_by_direction d
                LEFT JOIN dominant_headsign h USING (direction_id)
                LEFT JOIN primary_shape s USING (direction_id)
                LEFT JOIN pattern_size p USING (direction_id)
                ORDER BY d.direction_id
                """,
                (rows, index) -> {
                    Integer stopCount = (Integer) rows.getObject("stop_count");
                    // The driver will not convert numeric straight to Double.
                    java.math.BigDecimal lengthValue = rows.getBigDecimal("length_m");
                    Double length = lengthValue == null ? null : lengthValue.doubleValue();
                    return new RouteDirection(
                            rows.getShort("direction_id"),
                            rows.getString("headsign"),
                            rows.getInt("trip_count"),
                            stopCount,
                            length,
                            rows.getBoolean("is_loop"));
                },
                routeId, routeId, routeId, routeId);
    }

    /**
     * The route's geometry as GeoJSON.
     *
     * <p>Simplified in UTM zone 17N so the tolerance is real metres. Doing it in
     * Web Mercator would apply a tolerance 38% larger than asked for at this
     * latitude, and doing it in degrees would simplify differently along each axis.
     *
     * @param simplifyMetres tolerance, or null to return every point
     */
    public Optional<ShapeRow> findShape(String routeId, short directionId, Integer simplifyMetres) {
        String geometry = simplifyMetres == null
                ? "ST_AsGeoJSON(geom_wgs::geometry, 6)"
                : "ST_AsGeoJSON(ST_Transform(ST_SimplifyPreserveTopology("
                        + "ST_Transform(geom_wgs::geometry, 32617), ?), 4326), 6)";

        String sql = """
                SELECT shape_id, length_m, %s AS geojson
                FROM route_shapes
                WHERE route_id = ? AND direction_id = ? AND is_primary
                """.formatted(geometry);

        Object[] arguments = simplifyMetres == null
                ? new Object[] {routeId, directionId}
                : new Object[] {simplifyMetres, routeId, directionId};

        return jdbc
                .query(sql,
                        (rows, index) -> new ShapeRow(
                                rows.getString("shape_id"), rows.getDouble("length_m"), rows.getString("geojson")),
                        arguments)
                .stream()
                .findFirst();
    }

    /**
     * The stops a route serves, in order.
     *
     * <p>Taken from the trip with the most calls in that direction. A route has many
     * patterns — short turns, branches, diversions — and the longest one is the
     * closest thing to "the stops on this route" that the schedule actually
     * contains. Anything else would be a list Trackline invented.
     */
    public List<RouteStop> findStops(String routeId, short directionId) {
        return jdbc.query(
                """
                WITH primary_shape AS (
                    SELECT shape_id FROM route_shapes
                    WHERE route_id = ? AND direction_id = ? AND is_primary
                ),
                representative_trip AS (
                    SELECT t.trip_id
                    FROM trips t JOIN stop_times st ON st.trip_id = t.trip_id
                    WHERE t.route_id = ? AND t.direction_id = ?
                      -- Restricted to trips running the primary shape, so these
                      -- stops lie on the same line the shape endpoint returns.
                      -- Without this the longest pattern can belong to a different
                      -- variant, and a stop's distance along the route would exceed
                      -- the length of the route drawn on the map.
                      AND (t.shape_id = (SELECT shape_id FROM primary_shape)
                           OR NOT EXISTS (SELECT 1 FROM primary_shape))
                    GROUP BY t.trip_id
                    ORDER BY count(*) DESC, t.trip_id
                    LIMIT 1
                )
                SELECT s.stop_id, s.name, s.code,
                       ST_Y(s.location::geometry) AS lat,
                       ST_X(s.location::geometry) AS lon,
                       st.stop_sequence, st.shape_dist_traveled,
                       s.wheelchair_boarding, s.station_id, station.name AS station_name
                FROM representative_trip
                JOIN stop_times st ON st.trip_id = representative_trip.trip_id
                JOIN stops s ON s.stop_id = st.stop_id
                LEFT JOIN stations station ON station.id = s.station_id
                ORDER BY st.stop_sequence
                """,
                (rows, index) -> new RouteStop(
                        rows.getString("stop_id"),
                        rows.getString("name"),
                        rows.getString("code"),
                        rows.getDouble("lat"),
                        rows.getDouble("lon"),
                        rows.getInt("stop_sequence"),
                        metresAlongRoute(rows.getBigDecimal("shape_dist_traveled")),
                        rows.getShort("wheelchair_boarding"),
                        rows.getObject("station_id", Long.class),
                        rows.getString("station_name")),
                routeId, directionId, routeId, directionId);
    }

    public boolean exists(String routeId) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM routes WHERE route_id = ?)", Boolean.class, routeId);
        return Boolean.TRUE.equals(found);
    }

    /**
     * Converts the feed's distance-along-shape into metres.
     *
     * <p>GTFS does not define a unit for {@code shape_dist_traveled} — it only has
     * to be consistent — and the TTC publishes kilometres. Measured against the
     * geometry: a declared step of 0.0100 spans 10.0 m, and shape 1122835 declares
     * 17.3549 against a measured length of 17,409 m. Passing the raw value through
     * as metres would understate every distance by a factor of a thousand.
     */
    private static Double metresAlongRoute(java.math.BigDecimal kilometres) {
        return kilometres == null ? null : kilometres.doubleValue() * 1000;
    }

    private static Integer routeType(TransitMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case STREETCAR -> 0;
            case SUBWAY -> 1;
            case BUS -> 3;
            // A filter for OTHER would match nothing in this feed; -1 says so
            // honestly rather than silently returning every route.
            case OTHER -> -1;
        };
    }

    private static RowMapper<RouteSummary> summaryMapper() {
        return (rows, index) -> new RouteSummary(
                rows.getString("route_id"),
                rows.getString("short_name"),
                rows.getString("long_name"),
                TransitMode.fromRouteType(rows.getInt("route_type")),
                rows.getString("color"),
                rows.getString("text_color"),
                rows.getBoolean("supports_realtime"));
    }

    /** A primary shape with its geometry already rendered as GeoJSON. */
    public record ShapeRow(String shapeId, double lengthMetres, String geoJson) {
    }
}
