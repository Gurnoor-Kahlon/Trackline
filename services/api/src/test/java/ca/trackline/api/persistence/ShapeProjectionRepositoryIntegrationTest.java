package ca.trackline.api.persistence;

import java.util.ArrayList;
import java.util.List;

import ca.trackline.api.persistence.query.ShapeProjectionRepository;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ProjectionPoint;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ShapeProjection;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import ca.trackline.api.testsupport.TestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static ca.trackline.api.testsupport.ProjectionFixtures.insertFeedVersion;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertRoute;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertShape;
import static ca.trackline.api.testsupport.ProjectionFixtures.line;
import static ca.trackline.api.testsupport.ProjectionFixtures.metresAlongParallel;
import static ca.trackline.api.testsupport.ProjectionFixtures.metresPerDegreeLatitude;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The projection query against real PostGIS, on streets small enough to check by hand.
 *
 * <p>Geometry is where code that looks right can be wrong by a third: the shapes are
 * stored in Web Mercator, which stretches every distance by about 38% at Toronto's
 * latitude, and a query that measured in the planar system would return plausible
 * numbers that are all too big. So each expected value here is worked out from the
 * ellipsoid in Java, independently of PostGIS, and the two are compared.
 */
class ShapeProjectionRepositoryIntegrationTest extends PostgisIntegrationTest {

    private static final double LAT = 43.65;
    private static final double WEST = -79.40;
    private static final double MIDDLE = -79.39;
    private static final double EAST = -79.38;
    private static final double TANGENT_WINDOW = 15;

    private static final double METRES_PER_DEGREE_LATITUDE = metresPerDegreeLatitude(LAT);

    @Autowired
    private ShapeProjectionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long feedVersion;

    @BeforeEach
    void setUp() {
        clearAll();
        feedVersion = insertFeedVersion(jdbc);
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        ca.trackline.api.testsupport.ProjectionFixtures.clear(jdbc);
    }

    /** A latitude this many metres north of the street, worked out on the ellipsoid. */
    private static double northOf(double latitude, double metres) {
        return latitude + metres / METRES_PER_DEGREE_LATITUDE;
    }

    /**
     * An east-west street, as the TTC publishes a two-way route: one shape per
     * direction, running opposite ways along the same road. The westbound shape sits
     * 12 m to the north, as a real pair of directions sits either side of a centreline.
     */
    private void twoWayEastWestStreet(String routeId) {
        insertRoute(jdbc, feedVersion, routeId, routeId, "Test street " + routeId, 3);
        insertShape(jdbc, feedVersion, routeId + "-0", routeId, 0,
                line(WEST, LAT, MIDDLE, LAT, EAST, LAT), true, false);
        insertShape(jdbc, feedVersion, routeId + "-1", routeId, 1,
                line(EAST, northOf(LAT, 12), MIDDLE, northOf(LAT, 12), WEST, northOf(LAT, 12)), true, false);
    }

    private static ProjectionPoint at(String vehicleId, String routeId, double longitude, double latitude) {
        return new ProjectionPoint(vehicleId, routeId, latitude, longitude, null, null);
    }

    private ShapeProjection direction(List<ShapeProjection> rows, String vehicleId, int directionId) {
        return rows.stream()
                .filter(row -> row.vehicleId().equals(vehicleId) && row.directionId() == directionId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for %s direction %d in %s".formatted(
                        vehicleId, directionId, rows)));
    }

    @Test
    void progressAndOffsetMatchWhatTheEllipsoidSays() {
        twoWayEastWestStreet("r1");
        double longitude = -79.395;   // a quarter of the way from the west end
        double latitude = northOf(LAT, 5);

        List<ShapeProjection> rows = repository.project(List.of(at("v", "r1", longitude, latitude)), TANGENT_WINDOW);

        double streetLength = metresAlongParallel(LAT, WEST, EAST);
        ShapeProjection eastbound = direction(rows, "v", 0);
        ShapeProjection westbound = direction(rows, "v", 1);

        // Progress is measured from where each shape starts: the west end for the
        // eastbound shape, the east end for the westbound one.
        assertThat(eastbound.fraction() * eastbound.lengthMetres())
                .isCloseTo(metresAlongParallel(LAT, WEST, longitude), within(1.0));
        assertThat(westbound.fraction() * westbound.lengthMetres())
                .isCloseTo(metresAlongParallel(LAT, longitude, EAST), within(1.0));
        assertThat(eastbound.lengthMetres()).isCloseTo(streetLength, within(1.0));

        // The vehicle is 5 m north of the eastbound line and 7 m south of the
        // westbound one. These are true metres; in the planar system they would be 38% too large.
        assertThat(eastbound.offsetMetres()).isCloseTo(5.0, within(0.1));
        assertThat(westbound.offsetMetres()).isCloseTo(7.0, within(0.1));
    }

    @Test
    void theTwoShapesRunOppositeWaysAndTheTangentsSaySo() {
        twoWayEastWestStreet("r1");

        List<ShapeProjection> rows =
                repository.project(List.of(at("v", "r1", -79.395, northOf(LAT, 5))), TANGENT_WINDOW);

        assertThat(direction(rows, "v", 0).tangentBearing()).isCloseTo(90.0, within(0.5));
        assertThat(direction(rows, "v", 1).tangentBearing()).isCloseTo(270.0, within(0.5));
    }

    @Test
    void aNorthSouthStreetRunsDueNorthAndDueSouth() {
        insertRoute(jdbc, feedVersion, "ns", "ns", "North-south", 3);
        insertShape(jdbc, feedVersion, "ns-0", "ns", 0, line(MIDDLE, LAT, MIDDLE, LAT + 0.01), true, false);
        insertShape(jdbc, feedVersion, "ns-1", "ns", 1, line(MIDDLE, LAT + 0.01, MIDDLE, LAT), true, false);

        List<ShapeProjection> rows =
                repository.project(List.of(at("v", "ns", MIDDLE, LAT + 0.005)), TANGENT_WINDOW);

        // Reported as 0, never as 360: the range is [0, 360), so due north cannot be
        // confused with a vehicle's own 360 by a comparison that forgets they are equal.
        assertThat(direction(rows, "v", 0).tangentBearing()).isCloseTo(0.0, within(0.5));
        assertThat(direction(rows, "v", 1).tangentBearing()).isCloseTo(180.0, within(0.5));
    }

    @Test
    void theDirectionAtACornerIsSmoothedAcrossItNotReadFromOneSegment() {
        // East 805 m, then north: a right-angle turn at the middle vertex.
        insertRoute(jdbc, feedVersion, "corner", "corner", "Corner", 3);
        insertShape(jdbc, feedVersion, "corner-0", "corner", 0,
                line(WEST, LAT, MIDDLE, LAT, MIDDLE, LAT + 0.0072), true, false);

        List<ShapeProjection> rows = repository.project(List.of(at("v", "corner", MIDDLE, LAT)), TANGENT_WINDOW);

        // Fifteen metres either side of the vertex: 15 m west of it and 15 m north
        // of it, whose bearing is a diagonal. A single segment would say 90 or 0.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tangentBearing()).isCloseTo(45.0, within(3.0));
    }

    @Test
    void aVehicleBeyondTheEndIsPlacedAtTheEndAndKeepsItsDistance() {
        twoWayEastWestStreet("r1");

        List<ShapeProjection> rows =
                repository.project(List.of(at("v", "r1", WEST - 0.01, LAT)), TANGENT_WINDOW);

        ShapeProjection eastbound = direction(rows, "v", 0);
        assertThat(eastbound.fraction()).isEqualTo(0.0);
        // 0.01 degrees of longitude west of where the shape starts.
        assertThat(eastbound.offsetMetres()).isCloseTo(metresAlongParallel(LAT, WEST - 0.01, WEST), within(1.0));
    }

    @Test
    void thePreviousReadingIsProjectedOntoEveryShapeToo() {
        twoWayEastWestStreet("r1");
        ProjectionPoint moving = new ProjectionPoint("v", "r1", northOf(LAT, 5), -79.395, northOf(LAT, 5), -79.398);

        List<ShapeProjection> rows = repository.project(List.of(moving), TANGENT_WINDOW);

        // Moving east: it has advanced along the eastbound shape and retreated along
        // the westbound one, which is the whole of how movement picks a direction.
        ShapeProjection eastbound = direction(rows, "v", 0);
        ShapeProjection westbound = direction(rows, "v", 1);
        assertThat(eastbound.fraction() - eastbound.previousFraction()).isPositive();
        assertThat(westbound.fraction() - westbound.previousFraction()).isNegative();
        assertThat((eastbound.fraction() - eastbound.previousFraction()) * eastbound.lengthMetres())
                .isCloseTo(metresAlongParallel(LAT, -79.398, -79.395), within(1.0));
    }

    @Test
    void aVehicleWithNoPreviousReadingHasNoPreviousFraction() {
        twoWayEastWestStreet("r1");

        List<ShapeProjection> rows =
                repository.project(List.of(at("v", "r1", -79.395, northOf(LAT, 5))), TANGENT_WINDOW);

        assertThat(rows).allSatisfy(row -> assertThat(row.previousFraction()).isNull());
    }

    @Test
    void aFarVehicleReportsHowFarNotAnErrorOrAClampedValue() {
        twoWayEastWestStreet("r1");

        List<ShapeProjection> rows =
                repository.project(List.of(at("v", "r1", MIDDLE, LAT + 0.01)), TANGENT_WINDOW);

        // 0.01 degrees north is about 1,111 m; the westbound shape is 12 m closer.
        assertThat(direction(rows, "v", 0).offsetMetres()).isCloseTo(0.01 * METRES_PER_DEGREE_LATITUDE, within(0.5));
        assertThat(direction(rows, "v", 1).offsetMetres())
                .isCloseTo(0.01 * METRES_PER_DEGREE_LATITUDE - 12, within(0.5));
    }

    @Test
    void onlyThePrimaryShapeOfADirectionIsUsed() {
        twoWayEastWestStreet("r1");
        // A secondary variant of the eastbound pattern, 1.1 km to the north. A vehicle
        // sitting exactly on it must still be measured against the primary shape.
        insertShape(jdbc, feedVersion, "r1-0-variant", "r1", 0,
                line(WEST, LAT + 0.01, EAST, LAT + 0.01), false, false);

        List<ShapeProjection> rows = repository.project(List.of(at("v", "r1", MIDDLE, LAT + 0.01)), TANGENT_WINDOW);

        assertThat(rows).hasSize(2);
        assertThat(direction(rows, "v", 0).offsetMetres()).isGreaterThan(1_000);
    }

    @Test
    void aRouteWithNoPrimaryShapeYieldsNoRowsAtAll() {
        insertRoute(jdbc, feedVersion, "bare", "bare", "No shape", 3);

        List<ShapeProjection> rows = repository.project(List.of(at("v", "bare", MIDDLE, LAT)), TANGENT_WINDOW);

        // Silence, not a row of zeros: the caller must be able to tell "no geometry"
        // from "at the start of the route".
        assertThat(rows).isEmpty();
    }

    @Test
    void anEmptyBatchReturnsNothing() {
        assertThat(repository.project(List.of(), TANGENT_WINDOW)).isEmpty();
    }

    @Test
    void theLoopFlagIsCarriedThroughFromTheShape() {
        insertRoute(jdbc, feedVersion, "loop", "loop", "Loop", 3);
        insertShape(jdbc, feedVersion, "loop-0", "loop", 0,
                line(WEST, LAT, MIDDLE, LAT, MIDDLE, LAT + 0.005, WEST, LAT + 0.005, WEST, LAT), true, true);

        List<ShapeProjection> rows = repository.project(List.of(at("v", "loop", MIDDLE, LAT)), TANGENT_WINDOW);

        assertThat(rows).singleElement().satisfies(row -> assertThat(row.loop()).isTrue());
    }

    /**
     * After five executions the PostgreSQL driver would hand the statement to the
     * server as a prepared one and let it reuse a generic plan. For this query that
     * is a measured regression: the plan cannot see how long the arrays are, so it
     * assumes ten vehicles, picks a nested loop and runs 64% slower on a real batch.
     * Server-side preparation is therefore switched off for it, and this pins that.
     *
     * <p>Run on a single dedicated connection because prepared statements belong to
     * a session: on a pool the check could land on a different connection and pass
     * for the wrong reason.
     */
    @Test
    void theStatementIsNeverServerPreparedSoItIsAlwaysPlannedWithItsRealArrays() {
        twoWayEastWestStreet("r1");
        SingleConnectionDataSource dedicated = new SingleConnectionDataSource(
                TestContainers.POSTGRES.getJdbcUrl(),
                TestContainers.POSTGRES.getUsername(),
                TestContainers.POSTGRES.getPassword(),
                true);
        try {
            ShapeProjectionRepository single = new ShapeProjectionRepository(new JdbcTemplate(dedicated));
            for (int run = 0; run < 12; run++) {
                single.project(List.of(at("v", "r1", -79.395, northOf(LAT, 5))), TANGENT_WINDOW);
            }

            Integer prepared = new JdbcTemplate(dedicated).queryForObject(
                    "SELECT count(*) FROM pg_prepared_statements WHERE statement LIKE '%unnest%'", Integer.class);

            // Twelve runs is more than twice the driver's threshold of five, so a
            // statement left at the default would be sitting here by now.
            assertThat(prepared).isZero();
        } finally {
            dedicated.destroy();
        }
    }

    @Test
    void oneCallPlacesManyVehiclesOnManyRoutesWithEachMeasuredAgainstItsOwnRoute() {
        // Forty routes stacked 0.001 degrees apart, ten vehicles on each. If vehicles
        // were joined to the wrong route the offsets below would be tens of metres too large.
        List<ProjectionPoint> points = new ArrayList<>();
        for (int route = 0; route < 40; route++) {
            String routeId = "bulk" + route;
            double offset = route * 0.001;
            insertRoute(jdbc, feedVersion, routeId, routeId, "Bulk " + route, 3);
            insertShape(jdbc, feedVersion, routeId + "-0", routeId, 0,
                    line(WEST, LAT + offset, EAST, LAT + offset), true, false);
            insertShape(jdbc, feedVersion, routeId + "-1", routeId, 1,
                    line(EAST, LAT + offset, WEST, LAT + offset), true, false);
            for (int vehicle = 0; vehicle < 10; vehicle++) {
                points.add(at(routeId + "-v" + vehicle, routeId, WEST + 0.002 * (vehicle + 1), northOf(LAT + offset, 3)));
            }
        }

        List<ShapeProjection> rows = repository.project(points, TANGENT_WINDOW);

        assertThat(rows).hasSize(400 * 2);
        assertThat(rows).allSatisfy(row -> assertThat(row.offsetMetres()).isCloseTo(3.0, within(0.1)));
    }
}
