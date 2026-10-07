package ca.trackline.api.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Routes and shapes for the projection tests.
 *
 * <p>Two kinds, kept apart on purpose. Synthetic streets are built from coordinates
 * a test chooses, so the expected answer can be worked out independently of the code
 * under test. The real shapes are TTC geometry exported from an imported feed, so
 * the same code is also exercised on what the agency actually publishes, with all
 * its overlaps and kinks.
 */
public final class ProjectionFixtures {

    private static final String REAL_SHAPES = "/geometry/real-route-shapes.csv";

    /** WGS84, for the independent distance oracles. */
    private static final double SEMI_MAJOR_AXIS = 6_378_137.0;

    private static final double ECCENTRICITY_SQUARED = 0.00669437999014;

    private ProjectionFixtures() {
    }

    /** Empties everything these tests write, in an order foreign keys allow. */
    public static void clear(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE vehicle_observations");
        jdbc.execute("DELETE FROM ingestion_runs");
        jdbc.execute("""
                TRUNCATE stop_times, shape_points, trips, calendar_exceptions,
                         calendar_services, route_scheduled_headways, route_shapes,
                         stops, stations, routes
                """);
        jdbc.execute("DELETE FROM gtfs_feed_versions");
    }

    public static long insertFeedVersion(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO gtfs_feed_versions (sha256, source_url, downloaded_at, status)
                VALUES (?, 'https://example.test/fixture.zip', now(), 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                "c".repeat(64));
        return id == null ? -1 : id;
    }

    public static void insertRoute(
            JdbcTemplate jdbc, long feedVersionId, String routeId, String shortName, String longName, int routeType) {
        jdbc.update(
                """
                INSERT INTO routes (route_id, feed_version_id, short_name, long_name, route_type, supports_realtime)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                routeId, feedVersionId, shortName, longName, routeType, routeType != 1);
    }

    /**
     * Inserts a shape the way the importer builds one: planar geometry for locating
     * points along it, geography for distances, and a length measured on the geography.
     */
    public static void insertShape(
            JdbcTemplate jdbc,
            long feedVersionId,
            String shapeId,
            String routeId,
            int directionId,
            String wkt,
            boolean primary,
            boolean loop) {
        jdbc.update(
                """
                INSERT INTO route_shapes (
                    shape_id, feed_version_id, route_id, direction_id,
                    geom, geom_wgs, length_m, trip_count, is_primary, is_loop)
                VALUES (?, ?, ?, ?,
                        ST_Transform(ST_GeomFromText(?, 4326), 3857),
                        ST_GeomFromText(?, 4326)::geography,
                        ST_Length(ST_GeomFromText(?, 4326)::geography)::numeric(12, 2),
                        1, ?, ?)
                """,
                shapeId, feedVersionId, routeId, directionId, wkt, wkt, wkt, primary, loop);
    }

    /** Builds a WKT linestring from alternating longitude and latitude values. */
    public static String line(double... longitudeLatitudePairs) {
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int index = 0; index < longitudeLatitudePairs.length; index += 2) {
            if (index > 0) {
                wkt.append(", ");
            }
            wkt.append(longitudeLatitudePairs[index]).append(' ').append(longitudeLatitudePairs[index + 1]);
        }
        return wkt.append(')').toString();
    }

    /**
     * Loads the real shapes with their routes.
     *
     * @return the route ids loaded
     */
    public static List<String> loadRealShapes(JdbcTemplate jdbc, long feedVersionId) {
        List<String> routeIds = new ArrayList<>();
        try (InputStream stream = ProjectionFixtures.class.getResourceAsStream(REAL_SHAPES)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + REAL_SHAPES);
            }
            try (CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .get()
                    .parse(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (CSVRecord record : parser) {
                    String routeId = record.get("route_id");
                    if (!routeIds.contains(routeId)) {
                        insertRoute(
                                jdbc, feedVersionId, routeId,
                                record.get("short_name"), record.get("long_name"),
                                Integer.parseInt(record.get("route_type")));
                        routeIds.add(routeId);
                    }
                    int direction = Integer.parseInt(record.get("direction_id"));
                    insertShape(
                            jdbc, feedVersionId, "real-%s-%d".formatted(routeId, direction), routeId, direction,
                            record.get("wkt"), true, Boolean.parseBoolean(record.get("is_loop")));
                }
            }
        } catch (IOException cause) {
            throw new IllegalStateException("Could not read " + REAL_SHAPES, cause);
        }
        return routeIds;
    }

    // --- independent oracles ---------------------------------------------------------------

    /**
     * Length of an east-west arc along a parallel, from the ellipsoid formula.
     *
     * <p>Worked out here rather than asked of PostGIS, so a test comparing the two is
     * comparing independent calculations. For the few hundred metres the tests use,
     * the parallel and the geodesic between its ends differ by well under a millimetre.
     */
    public static double metresAlongParallel(double latitudeDegrees, double fromLongitude, double toLongitude) {
        double phi = Math.toRadians(latitudeDegrees);
        double sine = Math.sin(phi);
        double primeVerticalRadius = SEMI_MAJOR_AXIS / Math.sqrt(1 - ECCENTRICITY_SQUARED * sine * sine);
        return primeVerticalRadius * Math.cos(phi) * Math.toRadians(toLongitude - fromLongitude);
    }

    /** Length of one degree of latitude at the given latitude, in metres. */
    public static double metresPerDegreeLatitude(double latitudeDegrees) {
        double sine = Math.sin(Math.toRadians(latitudeDegrees));
        double denominator = Math.pow(1 - ECCENTRICITY_SQUARED * sine * sine, 1.5);
        return Math.toRadians(1) * SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED) / denominator;
    }
}
