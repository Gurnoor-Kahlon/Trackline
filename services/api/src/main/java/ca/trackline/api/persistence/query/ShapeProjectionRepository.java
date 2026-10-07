package ca.trackline.api.persistence.query;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.postgresql.PGStatement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Projects vehicle positions onto route shapes.
 *
 * <p>One statement for the whole batch, however many vehicles and routes it holds.
 * The positions travel as parallel arrays and are joined to the primary shapes
 * inside the database, so the cost of a cycle is a single round trip rather than a
 * query per route, let alone per vehicle. A real snapshot of 1,089 vehicles spans
 * 109 routes: this is one query where per-route batching would be 109.
 *
 * <p>Distances and lengths are in true metres. The shapes are stored in Web
 * Mercator, which exaggerates distance by about 38% at Toronto's latitude, so the
 * planar geometry is used only to locate a point along a line — a fraction, which
 * is close to unit-free — and the geography is used for every distance a person
 * will read.
 */
@Repository
public class ShapeProjectionRepository {

    /**
     * For each (vehicle, direction) pair on the vehicle's route:
     * <ul>
     *   <li>{@code offset_m} - geodesic distance from the vehicle to the shape;
     *   <li>{@code fraction} - where along the planar line the nearest point lies;
     *   <li>{@code previous_fraction} - the same for the previous reading, when given;
     *   <li>{@code tangent_bearing} - the compass bearing the shape runs in there.
     * </ul>
     *
     * <p>The tangent is read between two points either side of the vehicle rather
     * than from the single segment it falls on. A segment can be a few metres long
     * and noisy; a stretch about thirty metres across smooths that while still
     * resolving a corner. Mercator preserves angles, so a bearing taken in the
     * planar geometry is the true compass bearing.
     *
     * <p>{@code ST_LineLocatePoint} returns one fraction, the nearest. A shape that
     * visits the same spot twice therefore reports only one visit; docs/route-progress.md
     * records how often that applies to real vehicles and what it costs.
     */
    private static final String PROJECT = """
            WITH points AS (
                SELECT t.vehicle_id, t.route_id,
                       ST_Transform(ST_SetSRID(ST_MakePoint(t.lon, t.lat), 4326), 3857) AS planar,
                       ST_SetSRID(ST_MakePoint(t.lon, t.lat), 4326)::geography AS spheroid,
                       CASE WHEN t.previous_lon IS NULL THEN NULL
                            ELSE ST_Transform(
                                ST_SetSRID(ST_MakePoint(t.previous_lon, t.previous_lat), 4326), 3857)
                       END AS previous_planar
                FROM unnest(?::text[], ?::text[], ?::float8[], ?::float8[], ?::float8[], ?::float8[])
                     AS t(vehicle_id, route_id, lon, lat, previous_lon, previous_lat)
            ),
            located AS (
                SELECT p.vehicle_id, s.direction_id, s.geom, s.length_m::float8 AS length_m, s.is_loop,
                       ST_Distance(s.geom_wgs, p.spheroid) AS offset_m,
                       ST_LineLocatePoint(s.geom, p.planar) AS fraction,
                       CASE WHEN p.previous_planar IS NULL THEN NULL
                            ELSE ST_LineLocatePoint(s.geom, p.previous_planar)
                       END AS previous_fraction
                FROM points p
                JOIN route_shapes s ON s.route_id = p.route_id AND s.is_primary
            )
            SELECT l.vehicle_id, l.direction_id, l.length_m, l.is_loop,
                   l.offset_m, l.fraction, l.previous_fraction,
                   CASE WHEN ST_Equals(ends.before_point, ends.after_point) THEN NULL
                        ELSE mod(
                            degrees(atan2(ST_X(ends.after_point) - ST_X(ends.before_point),
                                          ST_Y(ends.after_point) - ST_Y(ends.before_point)))::numeric + 360, 360)
                   END AS tangent_bearing
            FROM located l
            CROSS JOIN LATERAL (
                SELECT ST_LineInterpolatePoint(l.geom, greatest(l.fraction - ?::float8 / l.length_m, 0)) AS before_point,
                       ST_LineInterpolatePoint(l.geom, least(l.fraction + ?::float8 / l.length_m, 1)) AS after_point
            ) ends
            """;

    private final JdbcTemplate jdbc;

    public ShapeProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param points              the vehicles to place, each with the route it is working
     * @param tangentWindowMetres half-length of the shape stretch used to read direction
     * @return one row per vehicle per direction its route has a primary shape for;
     *         a vehicle whose route has none gets no rows
     */
    public List<ShapeProjection> project(List<ProjectionPoint> points, double tangentWindowMetres) {
        if (points.isEmpty()) {
            return List.of();
        }

        return jdbc.query(
                connection -> prepare(connection, points, tangentWindowMetres),
                (rows, index) -> new ShapeProjection(
                        rows.getString("vehicle_id"),
                        rows.getShort("direction_id"),
                        rows.getDouble("length_m"),
                        rows.getBoolean("is_loop"),
                        rows.getDouble("offset_m"),
                        rows.getDouble("fraction"),
                        nullableDouble(rows, "previous_fraction"),
                        nullableDouble(rows, "tangent_bearing")));
    }

    private static PreparedStatement prepare(
            Connection connection, List<ProjectionPoint> points, double tangentWindowMetres) throws SQLException {
        int size = points.size();
        String[] vehicleIds = new String[size];
        String[] routeIds = new String[size];
        Double[] longitudes = new Double[size];
        Double[] latitudes = new Double[size];
        Double[] previousLongitudes = new Double[size];
        Double[] previousLatitudes = new Double[size];

        for (int index = 0; index < size; index++) {
            ProjectionPoint point = points.get(index);
            vehicleIds[index] = point.vehicleId();
            routeIds[index] = point.routeId();
            longitudes[index] = point.longitude();
            latitudes[index] = point.latitude();
            previousLongitudes[index] = point.previousLongitude();
            previousLatitudes[index] = point.previousLatitude();
        }

        PreparedStatement statement = connection.prepareStatement(PROJECT);
        // Always planned with the real parameters, never from a cached generic plan.
        // After five executions the driver would let Postgres reuse one plan, and a
        // generic plan cannot see how long the arrays are: it guesses ten vehicles
        // where there are about a thousand, believes a nested loop is cheap, and
        // re-reads every wide shape row once per vehicle. Measured on a real batch of
        // 947 vehicles that plan took 207 ms against 126 ms for the one chosen with
        // the arrays in view. Planning costs about a millisecond.
        statement.unwrap(PGStatement.class).setPrepareThreshold(0);
        statement.setArray(1, array(connection, "text", vehicleIds));
        statement.setArray(2, array(connection, "text", routeIds));
        statement.setArray(3, array(connection, "float8", longitudes));
        statement.setArray(4, array(connection, "float8", latitudes));
        statement.setArray(5, array(connection, "float8", previousLongitudes));
        statement.setArray(6, array(connection, "float8", previousLatitudes));
        statement.setDouble(7, tangentWindowMetres);
        statement.setDouble(8, tangentWindowMetres);
        return statement;
    }

    private static Array array(Connection connection, String type, Object[] values) throws SQLException {
        return connection.createArrayOf(type, values);
    }

    private static Double nullableDouble(ResultSet rows, String column) throws SQLException {
        double value = rows.getDouble(column);
        return rows.wasNull() ? null : value;
    }

    /**
     * A vehicle to place.
     *
     * @param previousLatitude  earlier reading of the same vehicle, or null
     * @param previousLongitude earlier reading of the same vehicle, or null
     */
    public record ProjectionPoint(
            String vehicleId,
            String routeId,
            double latitude,
            double longitude,
            Double previousLatitude,
            Double previousLongitude) {
    }

    /**
     * One vehicle's relationship to one direction's shape.
     *
     * @param offsetMetres     geodesic distance to the shape
     * @param fraction         position along the shape, 0 to 1
     * @param previousFraction position of the previous reading, or null
     * @param tangentBearing   compass bearing of the shape there, or null if it has no direction
     */
    public record ShapeProjection(
            String vehicleId,
            short directionId,
            double lengthMetres,
            boolean loop,
            double offsetMetres,
            double fraction,
            Double previousFraction,
            Double tangentBearing) {
    }
}
