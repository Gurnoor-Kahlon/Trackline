package ca.trackline.api.analytics.progress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.trackline.api.gtfs.realtime.FeedEntityValidator;
import ca.trackline.api.gtfs.realtime.FeedSnapshot;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.gtfs.realtime.RealtimeFeedParser;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.persistence.query.ShapeProjectionRepository;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ProjectionPoint;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ShapeProjection;
import ca.trackline.api.testsupport.FeedFixtures;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertFeedVersion;
import static ca.trackline.api.testsupport.ProjectionFixtures.loadRealShapes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection on the geometry the TTC actually publishes.
 *
 * <p>Thirty real shapes, both directions of the fifteen routes in the recorded
 * vehicle snapshot. Real shapes are untidy in ways a hand-built street is not: they
 * double back, cross themselves, and hug corners, and these tests are where that
 * shows. Numbers measured here are logged and recorded in docs/route-progress.md.
 */
class RealGeometryProjectionIntegrationTest extends PostgisIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RealGeometryProjectionIntegrationTest.class);

    private static final double TANGENT_WINDOW = 15;

    @Autowired
    private RouteProgressProjector projector;

    @Autowired
    private ShapeProjectionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void loadShapes() {
        clear(jdbc);
        loadRealShapes(jdbc, insertFeedVersion(jdbc));
    }

    @AfterEach
    void tearDown() {
        clear(jdbc);
    }

    // --- progress, against an independent oracle ------------------------------------------

    /**
     * Every sampled vertex of every real shape, projected back, should land where it
     * came from. The oracle is the geodesic length of the polyline up to that vertex,
     * summed segment by segment — a different calculation from locating a fraction
     * on the planar line and scaling it.
     *
     * <p>A vertex can only fail this if the shape visits the same place twice and the
     * query returns the other visit. So the second half of the test insists that every
     * miss is exactly that: the point the query found lies on top of the vertex.
     */
    @Test
    void projectedProgressMatchesTheGeodesicLengthAlongTheRealShape() {
        List<Sample> samples = new ArrayList<>();
        for (String shapeId : shapeIds()) {
            samples.addAll(vertexSamples(shapeId));
        }
        assertThat(samples).hasSizeGreaterThan(1_000);

        List<ProjectionPoint> points = samples.stream()
                .map(sample -> new ProjectionPoint(sample.key(), sample.routeId(), sample.latitude(), sample.longitude(), null, null))
                .toList();
        Map<String, Sample> byKey = new java.util.HashMap<>();
        samples.forEach(sample -> byKey.put(sample.key(), sample));

        // Each vertex is projected onto both directions of its route; the one that
        // matters is the shape the vertex was taken from.
        Map<String, ShapeProjection> placed = new java.util.HashMap<>();
        for (ShapeProjection row : repository.project(points, TANGENT_WINDOW)) {
            if (row.directionId() == byKey.get(row.vehicleId()).directionId()) {
                placed.put(row.vehicleId(), row);
            }
        }

        List<Double> errors = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        int unexplained = 0;
        for (Sample sample : samples) {
            ShapeProjection row = placed.get(sample.key());
            double progress = row.fraction() * row.lengthMetres();
            double error = Math.abs(progress - sample.exactMetres());
            errors.add(error);
            if (error > 20) {
                misses.add("%s vertex %d: off by %.0f m".formatted(sample.shapeId(), sample.index(), error));
                // The returned fraction must point at (almost) the same place as the vertex.
                double gap = distanceFromVertexToLocatedPoint(sample, row.fraction());
                if (gap > 1.0) {
                    unexplained++;
                }
            }
        }

        errors.sort(Double::compare);
        double median = errors.get(errors.size() / 2);
        double p99 = errors.get((int) (errors.size() * 0.99));
        log.info("Real-geometry progress: {} vertices, median error {} m, p99 {} m, max {} m, {} over 20 m ({}); "
                        + "misses not explained by a revisited location: {}",
                samples.size(), round(median), round(p99), round(errors.get(errors.size() - 1)),
                misses.size(), misses.size() > 8 ? misses.subList(0, 8) : misses, unexplained);

        // The planar fraction scaled by true length is within 15.7 m of the geodesic
        // everywhere on the network; the median is far smaller.
        assertThat(median).isLessThan(5.0);
        assertThat(errors.stream().filter(error -> error <= 20).count())
                .as("share of vertices within 20 m")
                .isGreaterThanOrEqualTo((long) Math.floor(errors.size() * 0.97));
        // A miss that is not a revisited location would be a mistake in the maths.
        assertThat(unexplained).as("misses not explained by a revisited location").isZero();
    }

    // --- the recorded vehicles ------------------------------------------------------------

    @Test
    void everyVehicleInTheRecordedSnapshotIsPlacedOrExplained() {
        List<VehicleSnapshot> vehicles = recordedVehicles();
        long routed = vehicles.stream().filter(VehicleSnapshot::hasRouteAssignment).count();
        assertThat(vehicles).hasSize(40);
        assertThat(routed).isEqualTo(23);

        RouteProgressSnapshot result = projector.project(vehicles);

        assertThat(result.size()).isEqualTo(vehicles.size());
        Map<ProjectionStatus, Long> counts = result.countsByStatus();
        log.info("Recorded snapshot placement: {} of {} route-assigned; unknown reasons {}",
                counts, routed, result.unknownReasons());

        // The seventeen the feed assigned to no route are stated as such, not dropped
        // and not forced onto a route.
        assertThat(counts.get(ProjectionStatus.NO_ROUTE)).isEqualTo(17L);
        assertThat(counts).doesNotContainKey(ProjectionStatus.NO_GEOMETRY);

        for (VehicleSnapshot vehicle : vehicles) {
            RouteProgress progress = result.get(vehicle.vehicleId()).orElseThrow();
            if (progress.status().isAssigned()) {
                assertThat(progress.offsetMetres()).isLessThanOrEqualTo(150);
                assertThat(progress.progressFraction()).isBetween(0.0, 1.0);
                assertThat(progress.directionId()).isIn((short) 0, (short) 1);
            } else if (progress.status() == ProjectionStatus.OFF_ROUTE) {
                assertThat(progress.offsetMetres()).isGreaterThan(150);
            }
        }

        // Not asserted to be everything: some vehicles are legitimately off route or
        // stationary at a terminal. But a projection that placed almost none of the
        // fleet would be broken, whatever the explanation.
        long assigned = counts.getOrDefault(ProjectionStatus.ASSIGNED_BY_BEARING, 0L)
                + counts.getOrDefault(ProjectionStatus.ASSIGNED_BY_MOVEMENT, 0L);
        assertThat(assigned).isGreaterThanOrEqualTo(routed / 2);
    }

    // --- direction inference, against geometry whose answer is known -----------------------

    /**
     * Drives a vehicle along each real shape and asks which direction it is placed in.
     *
     * <p>The right answer is known by construction: the vehicle is on that shape, so
     * its direction is that shape's. The heading is computed from the neighbouring
     * vertices, independently of how the query reads the tangent, and is perfect —
     * no sensor noise. So this is a best case, and says how often the inference could
     * be right at all given clean input, not how often it is right in service.
     */
    @Test
    void aVehicleDrivingAlongARealShapeIsPlacedInThatShapesDirection() {
        List<VehicleSnapshot> vehicles = new ArrayList<>();
        Map<String, Short> truth = new java.util.HashMap<>();

        for (String shapeId : shapeIds()) {
            for (Sample sample : vertexSamples(shapeId)) {
                Double heading = headingAt(shapeId, sample.index());
                if (heading == null) {
                    continue;
                }
                String id = sample.key();
                vehicles.add(new VehicleSnapshot(
                        id, sample.routeId(), null, sample.latitude(), sample.longitude(),
                        heading.floatValue(), 8f, null, null, null, null, Instant.parse("2026-10-02T04:00:00Z")));
                truth.put(id, (short) sample.directionId());
            }
        }

        RouteProgressSnapshot result = projector.project(vehicles);

        Map<String, Long> outcome = new java.util.TreeMap<>();
        Map<ProjectionStatus, Long> byStatus = new EnumMap<>(ProjectionStatus.class);
        List<String> wrongCases = new ArrayList<>();
        for (VehicleSnapshot vehicle : vehicles) {
            RouteProgress progress = result.get(vehicle.vehicleId()).orElseThrow();
            byStatus.merge(progress.status(), 1L, Long::sum);
            if (progress.status().isAssigned()) {
                boolean correct = progress.directionId().equals(truth.get(vehicle.vehicleId()));
                outcome.merge(correct ? "correct" : "WRONG", 1L, Long::sum);
                if (!correct) {
                    wrongCases.add("%s heading %.0f offset %.1f m".formatted(
                            vehicle.vehicleId(), (double) vehicle.bearing(), progress.offsetMetres()));
                }
            }
        }
        long assigned = outcome.values().stream().mapToLong(Long::longValue).sum();
        long wrong = outcome.getOrDefault("WRONG", 0L);
        log.info("Direction inference on {} real-shape samples with a clean heading: {} -> statuses {}, "
                        + "{} wrong of {} assigned; wrong cases {}",
                vehicles.size(), outcome, byStatus, wrong, assigned, wrongCases);

        assertThat(vehicles.size()).isGreaterThan(500);
        // The cost of a wrong direction is a vehicle counted among the wrong vehicles'
        // spacing, so it is held to a much tighter bound than "unknown", which merely
        // leaves a vehicle out.
        assertThat(wrong * 1.0 / assigned).as("share of assigned vehicles placed in the wrong direction")
                .isLessThan(0.01);
        assertThat(assigned * 1.0 / vehicles.size()).as("share of vehicles given a direction")
                .isGreaterThan(0.80);
    }

    // --- helpers --------------------------------------------------------------------------------

    private List<String> shapeIds() {
        return jdbc.queryForList("SELECT shape_id FROM route_shapes ORDER BY shape_id", String.class);
    }

    private List<VehicleSnapshot> recordedVehicles() {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(FeedFixtures.bytes("vehicles.pb"));
            Instant recorded = Instant.ofEpochSecond(message.getHeader().getTimestamp());
            FeedSnapshot snapshot = new RealtimeFeedParser().parse(
                    RealtimeFeed.VEHICLE_POSITIONS,
                    FeedFixtures.bytes("vehicles.pb"),
                    new FeedEntityValidator(Set.of()),
                    recorded);
            return snapshot.vehicles();
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Interior vertices of a shape, every tenth, with the geodesic length of the
     * line up to each. Summed segment by segment in the database, which is a different
     * route to the number than scaling a planar fraction.
     */
    private List<Sample> vertexSamples(String shapeId) {
        return jdbc.query(
                """
                WITH shape AS (
                    SELECT shape_id, route_id, direction_id, geom_wgs::geometry AS geometry
                    FROM route_shapes WHERE shape_id = ?
                ),
                vertices AS (
                    SELECT s.shape_id, s.route_id, s.direction_id, s.geometry,
                           (dp).path[1] AS idx, (dp).geom AS point
                    FROM shape s, LATERAL ST_DumpPoints(s.geometry) dp
                )
                SELECT v.shape_id, v.route_id, v.direction_id, v.idx,
                       ST_Y(v.point) AS latitude, ST_X(v.point) AS longitude,
                       ST_Length(ST_MakeLine(ARRAY(
                           SELECT (d2).geom FROM ST_DumpPoints(v.geometry) d2
                           WHERE (d2).path[1] <= v.idx ORDER BY (d2).path[1]))::geography) AS exact_m
                FROM vertices v
                WHERE v.idx >= 6 AND v.idx % 10 = 0
                  AND v.idx <= (SELECT ST_NPoints(geometry) - 5 FROM shape)
                ORDER BY v.idx
                """,
                (rows, index) -> new Sample(
                        rows.getString("shape_id"),
                        rows.getString("route_id"),
                        rows.getInt("direction_id"),
                        rows.getInt("idx"),
                        rows.getDouble("latitude"),
                        rows.getDouble("longitude"),
                        rows.getDouble("exact_m")),
                shapeId);
    }

    /**
     * The compass bearing of a shape across three vertices either side of the given
     * one, or null where they are too close together for a bearing to mean anything.
     * Standard forward-azimuth formula, written out here rather than asked of PostGIS.
     */
    private Double headingAt(String shapeId, int index) {
        List<double[]> around = jdbc.query(
                """
                SELECT ST_Y((dp).geom) AS lat, ST_X((dp).geom) AS lon
                FROM route_shapes s, LATERAL ST_DumpPoints(s.geom_wgs::geometry) dp
                WHERE s.shape_id = ? AND (dp).path[1] IN (?, ?)
                ORDER BY (dp).path[1]
                """,
                (rows, i) -> new double[] {rows.getDouble("lat"), rows.getDouble("lon")},
                shapeId, index - 3, index + 3);
        if (around.size() != 2) {
            return null;
        }
        double[] from = around.get(0);
        double[] to = around.get(1);
        if (metresBetween(from, to) < 15) {
            return null;
        }
        double phi1 = Math.toRadians(from[0]);
        double phi2 = Math.toRadians(to[0]);
        double dLambda = Math.toRadians(to[1] - from[1]);
        double y = Math.sin(dLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** How far the point the query found on the line is from the vertex we started at. */
    private double distanceFromVertexToLocatedPoint(Sample sample, double fraction) {
        Double gap = jdbc.queryForObject(
                """
                SELECT ST_Distance(
                           ST_Transform(ST_LineInterpolatePoint(geom, ?), 4326)::geography,
                           ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                FROM route_shapes WHERE shape_id = ?
                """,
                Double.class, fraction, sample.longitude(), sample.latitude(), sample.shapeId());
        return gap == null ? Double.MAX_VALUE : gap;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** Equirectangular distance in metres between two {latitude, longitude} pairs. */
    private static double metresBetween(double[] from, double[] to) {
        double metresPerDegree = 111_320;
        double northing = (to[0] - from[0]) * metresPerDegree;
        double easting = (to[1] - from[1]) * metresPerDegree * Math.cos(Math.toRadians((from[0] + to[0]) / 2));
        return Math.hypot(northing, easting);
    }

    private record Sample(
            String shapeId,
            String routeId,
            int directionId,
            int index,
            double latitude,
            double longitude,
            double exactMetres) {

        String key() {
            return shapeId + "#" + index;
        }
    }
}
