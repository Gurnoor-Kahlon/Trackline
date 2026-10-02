package ca.trackline.api.gtfs.schedule.derive;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.trackline.api.gtfs.schedule.derive.ShapeSelection.RouteDirection;
import ca.trackline.api.gtfs.schedule.derive.ShapeSelection.ShapeCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the imported shape points into route geometry.
 *
 * <p>Each shape becomes a linestring held twice: metre-based EPSG:3857 for
 * {@code ST_LineLocatePoint}, which needs planar units, and a 4326 geography for
 * display and for true distances.
 *
 * <p>{@code length_m} is measured on the geography, not on the projected geometry.
 * Web Mercator exaggerates distance by about 38% at Toronto's latitude, so a
 * length taken from the 3857 line would make every route appear a third longer
 * than it is and would corrupt every spacing figure derived from it.
 */
@Component
public class RouteShapeBuilder {

    private static final Logger log = LoggerFactory.getLogger(RouteShapeBuilder.class);

    /**
     * Builds one row per shape.
     *
     * <p>A shape is attributed to the route and direction whose trips use it most.
     * In the current feed no shape is shared across routes or directions at all, but
     * the feed does not guarantee that, and silently picking an arbitrary owner
     * would misplace vehicles.
     */
    private static final String INSERT_SHAPES = """
            INSERT INTO route_shapes (
                shape_id, feed_version_id, route_id, direction_id,
                geom, geom_wgs, length_m, trip_count, is_primary, is_loop)
            WITH owner AS (
                SELECT shape_id, route_id, direction_id, count(*)::int AS trip_count,
                       row_number() OVER (
                           PARTITION BY shape_id
                           ORDER BY count(*) DESC, route_id, direction_id) AS rank
                FROM trips
                WHERE shape_id IS NOT NULL
                GROUP BY shape_id, route_id, direction_id
            ),
            line AS (
                SELECT shape_id,
                       ST_MakeLine(
                           ST_SetSRID(ST_MakePoint(lon, lat), 4326) ORDER BY shape_pt_sequence) AS geom_4326,
                       count(*) AS point_count
                FROM shape_points
                GROUP BY shape_id
            )
            SELECT o.shape_id,
                   ?,
                   o.route_id,
                   o.direction_id,
                   ST_Transform(l.geom_4326, 3857),
                   l.geom_4326::geography,
                   ST_Length(l.geom_4326::geography)::numeric(12, 2),
                   o.trip_count,
                   FALSE,
                   FALSE
            FROM owner o
            JOIN line l USING (shape_id)
            WHERE o.rank = 1
              -- Two distinct points are the minimum for a line. A shape with fewer
              -- describes no path, and nothing can be projected onto it.
              AND l.point_count >= 2
              AND ST_Length(l.geom_4326::geography) > 0
            """;

    /** Endpoint separation in true metres, used to decide whether a shape loops. */
    private static final String SELECT_SHAPE_FACTS = """
            SELECT shape_id, route_id, direction_id, trip_count,
                   ST_Distance(
                       ST_StartPoint(geom_wgs::geometry)::geography,
                       ST_EndPoint(geom_wgs::geometry)::geography) AS endpoint_gap_m
            FROM route_shapes
            """;

    public BuildSummary build(Connection connection, long feedVersionId) throws SQLException {
        int inserted;
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SHAPES)) {
            statement.setLong(1, feedVersionId);
            inserted = statement.executeUpdate();
        }

        List<ShapeFact> facts = readFacts(connection);
        Set<String> primaries = ShapeSelection.choosePrimaries(groupByRouteDirection(facts));
        List<String> loops = facts.stream()
                .filter(fact -> ShapeSelection.isLoop(fact.endpointGapMetres()))
                .map(ShapeFact::shapeId)
                .toList();

        applyFlag(connection, "is_primary", primaries);
        applyFlag(connection, "is_loop", loops);

        log.info("Built {} route shapes, {} primary, {} loops", inserted, primaries.size(), loops.size());
        return new BuildSummary(inserted, primaries.size(), loops.size());
    }

    private static List<ShapeFact> readFacts(Connection connection) throws SQLException {
        List<ShapeFact> facts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT_SHAPE_FACTS)) {
            while (rows.next()) {
                facts.add(new ShapeFact(
                        rows.getString("shape_id"),
                        rows.getString("route_id"),
                        rows.getShort("direction_id"),
                        rows.getInt("trip_count"),
                        rows.getDouble("endpoint_gap_m")));
            }
        }
        return facts;
    }

    private static Map<RouteDirection, List<ShapeCandidate>> groupByRouteDirection(List<ShapeFact> facts) {
        Map<RouteDirection, List<ShapeCandidate>> grouped = new HashMap<>();
        for (ShapeFact fact : facts) {
            grouped.computeIfAbsent(
                            new RouteDirection(fact.routeId(), fact.directionId()), key -> new ArrayList<>())
                    .add(new ShapeCandidate(fact.shapeId(), fact.tripCount()));
        }
        return grouped;
    }

    private static void applyFlag(Connection connection, String column, Iterable<String> shapeIds)
            throws SQLException {
        String sql = "UPDATE route_shapes SET %s = TRUE WHERE shape_id = ?".formatted(column);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String shapeId : shapeIds) {
                statement.setString(1, shapeId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private record ShapeFact(
            String shapeId, String routeId, short directionId, int tripCount, double endpointGapMetres) {
    }

    /**
     * @param shapes  shapes built
     * @param primary shapes marked primary, one per route and direction
     * @param loops   shapes whose endpoints meet
     */
    public record BuildSummary(int shapes, int primary, int loops) {
    }
}
