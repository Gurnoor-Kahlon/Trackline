package ca.trackline.api.gtfs.schedule.derive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import ca.trackline.api.gtfs.schedule.GtfsImportResult;
import ca.trackline.api.gtfs.schedule.GtfsImportService;
import ca.trackline.api.gtfs.schedule.ScheduleCoverageService;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derivation running against real PostGIS on the committed feed subset.
 *
 * <p>Geometry is the part that cannot be verified by reasoning about the code:
 * a projection mistake produces linestrings that look plausible and measure wrong,
 * so lengths and validity are checked against the database itself.
 */
class NetworkDerivationIntegrationTest extends PostgisIntegrationTest {

    @Autowired
    private GtfsImportService importService;

    @Autowired
    private ScheduleCoverageService coverageService;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private GtfsImportResult result;

    @BeforeEach
    void importFixture() throws IOException {
        clearEverything();

        Path fixture = tempDir.resolve("ttc-subset.zip");
        try (InputStream source = getClass().getResourceAsStream("/gtfs/ttc-subset.zip")) {
            if (source == null) {
                throw new IllegalStateException("Test fixture /gtfs/ttc-subset.zip is missing");
            }
            Files.copy(source, fixture);
        }
        result = importService.importArchive(
                fixture, "https://example.test/ttc.zip", "e".repeat(64), System.nanoTime());
    }

    @AfterEach
    void clearWhatThisTestCommitted() {
        clearEverything();
    }

    private void clearEverything() {
        jdbc.execute("""
                TRUNCATE stop_times, shape_points, trips, calendar_exceptions,
                         calendar_services, route_scheduled_headways, route_shapes,
                         stops, stations, routes
                """);
        jdbc.execute("DELETE FROM gtfs_feed_versions");
    }

    @Test
    void derivationRunsAsPartOfTheImport() {
        // An active network without geometry could not place a vehicle or draw a
        // map, so the import is not complete until both exist.
        assertThat(result.rowCounts())
                .containsKey("route_shapes")
                .containsKey("stations")
                .containsKey("route_scheduled_headways");
        assertThat(count("route_shapes")).isPositive();
    }

    @Test
    void everyShapeIsValidGeometryInBothProjections() {
        Integer invalid = jdbc.queryForObject(
                "SELECT count(*) FROM route_shapes WHERE NOT ST_IsValid(geom) OR NOT ST_IsValid(geom_wgs::geometry)",
                Integer.class);
        assertThat(invalid).isZero();

        Integer wrongSrid = jdbc.queryForObject(
                """
                SELECT count(*) FROM route_shapes
                WHERE ST_SRID(geom) <> 3857 OR ST_SRID(geom_wgs::geometry) <> 4326
                """,
                Integer.class);
        assertThat(wrongSrid).isZero();

        Integer tooFewPoints = jdbc.queryForObject(
                "SELECT count(*) FROM route_shapes WHERE ST_NPoints(geom) < 2", Integer.class);
        assertThat(tooFewPoints).isZero();
    }

    @Test
    void lengthIsTrueMetresNotWebMercatorMetres() {
        // Web Mercator exaggerates distance by about 38% at Toronto's latitude.
        // Taking length from the projected line would make every route appear a
        // third longer and corrupt every spacing figure derived from it.
        Double storedVersusGeography = jdbc.queryForObject(
                """
                SELECT max(abs(length_m - ST_Length(geom_wgs)) / ST_Length(geom_wgs))
                FROM route_shapes
                """,
                Double.class);
        assertThat(storedVersusGeography).isNotNull().isLessThan(0.001);

        Double mercatorRatio = jdbc.queryForObject(
                "SELECT avg(ST_Length(geom) / length_m) FROM route_shapes", Double.class);
        assertThat(mercatorRatio).isNotNull().isGreaterThan(1.3);
    }

    @Test
    void shapeLengthsArePlausibleForTheRoutesInTheFixture() {
        // 501 Queen is one of the longest streetcar routes in the world; its
        // shapes should measure in the tens of kilometres, not hundreds of metres.
        Double queenLongest = jdbc.queryForObject(
                "SELECT max(length_m) FROM route_shapes WHERE route_id = '501'", Double.class);
        assertThat(queenLongest).isNotNull().isBetween(10_000.0, 50_000.0);

        Integer implausible = jdbc.queryForObject(
                "SELECT count(*) FROM route_shapes WHERE length_m < 100 OR length_m > 100000", Integer.class);
        assertThat(implausible).isZero();
    }

    @Test
    void exactlyOneShapePerRouteAndDirectionIsPrimary() {
        List<String> withoutExactlyOne = jdbc.queryForList(
                """
                SELECT route_id || '/' || direction_id
                FROM route_shapes
                GROUP BY route_id, direction_id
                HAVING count(*) FILTER (WHERE is_primary) <> 1
                """,
                String.class);

        // Vehicles are projected onto the primary shape, so an ambiguous or missing
        // one would leave a whole route and direction unplaceable.
        assertThat(withoutExactlyOne).isEmpty();
    }

    @Test
    void progressAlongAPrimaryShapeCanBeMeasured() {
        // The operation every later spacing and bunching figure is built on.
        Double fraction = jdbc.queryForObject(
                """
                SELECT ST_LineLocatePoint(geom, ST_LineInterpolatePoint(geom, 0.25))
                FROM route_shapes WHERE is_primary AND route_id = '501' LIMIT 1
                """,
                Double.class);

        assertThat(fraction).isNotNull().isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void platformsAreGroupedIntoStationsAndNothingElseIs() {
        Integer stations = count("stations");
        assertThat(stations).isPositive();

        Integer attached = jdbc.queryForObject(
                "SELECT count(*) FROM stops WHERE station_id IS NOT NULL", Integer.class);
        assertThat(attached).isPositive();

        // Only platforms join a station. A surface stop merely named after one
        // must stay out of it.
        Integer wronglyAttached = jdbc.queryForObject(
                "SELECT count(*) FROM stops WHERE station_id IS NOT NULL AND name NOT ILIKE '%Platform'",
                Integer.class);
        assertThat(wronglyAttached).isZero();

        String method = jdbc.queryForObject(
                "SELECT DISTINCT derivation_method FROM stations", String.class);
        assertThat(method).isEqualTo(StationClusterer.DERIVATION_METHOD);
    }

    @Test
    void aStationsPlatformsAreCloseTogetherAndItsCentroidSitsAmongThem() {
        Double widestSpread = jdbc.queryForObject(
                """
                SELECT max(spread) FROM (
                    SELECT max(ST_Distance(a.location, st.centroid)) AS spread
                    FROM stations st JOIN stops a ON a.station_id = st.id
                    GROUP BY st.id) spreads
                """,
                Double.class);

        assertThat(widestSpread).isNotNull().isLessThan((double) StationClusterer.CLUSTER_RADIUS_METRES);

        Integer countMismatch = jdbc.queryForObject(
                """
                SELECT count(*) FROM stations st
                WHERE st.stop_count <> (SELECT count(*) FROM stops WHERE station_id = st.id)
                """,
                Integer.class);
        assertThat(countMismatch).isZero();
    }

    @Test
    void scheduledHeadwaysDescribeRealGapsOnly() {
        Integer rows = count("route_scheduled_headways");
        assertThat(rows).isPositive();

        Integer unusable = jdbc.queryForObject(
                """
                SELECT count(*) FROM route_scheduled_headways
                WHERE median_headway_seconds <= 0 OR gap_count < 1 OR departure_count < 2
                """,
                Integer.class);
        assertThat(unusable).isZero();

        // A transit headway measured in days would mean the gap arithmetic wrapped.
        Integer absurd = jdbc.queryForObject(
                "SELECT count(*) FROM route_scheduled_headways WHERE median_headway_seconds > 86400", Integer.class);
        assertThat(absurd).isZero();
    }

    @Test
    void reportsThatTheFeedDoesNotCoverADayOutsideItsWindow() {
        ScheduleCoverageService.ScheduleCoverage coverage =
                coverageService.coverageOn(result.feedStartDate().minusDays(1));

        // The real case: a feed downloaded on 27 September described 30 September
        // onwards and said nothing about that day. Schedule-derived figures must
        // be able to detect exactly this instead of comparing against a schedule
        // that does not apply.
        assertThat(coverage.covered()).isFalse();
        assertThat(coverage.scheduleMissing()).isFalse();
        assertThat(coverage.feedStartDate()).isEqualTo(result.feedStartDate());
    }

    @Test
    void reportsCoverageForADayInsideTheWindow() {
        LocalDate inside = result.feedStartDate().plusDays(1);

        ScheduleCoverageService.ScheduleCoverage coverage = coverageService.coverageOn(inside);

        assertThat(coverage.covered()).isTrue();
        assertThat(coverage.feedVersionId()).isEqualTo(result.feedVersionId());
    }

    @Test
    void todaysCoverageIsTheTorontoDayNotTheUtcOne() {
        // 03:00 UTC is still the previous evening in Toronto. Asking UTC what day
        // it is would roll the service day over four hours early every night, and
        // schedule-derived figures would be compared against the wrong day.
        ScheduleCoverageService torontoClock = new ScheduleCoverageService(
                jdbc, Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC));

        assertThat(torontoClock.coverageToday().date()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void derivedGeometryIsReplacedWithTheNetworkOnReimport() {
        int shapesBefore = count("route_shapes");
        int stationsBefore = count("stations");

        importService.importArchive(
                tempDir.resolve("ttc-subset.zip"), "https://example.test/ttc.zip",
                "f".repeat(64), System.nanoTime());

        // Derived rows describe the network they came from; leaving them behind
        // would attach old geometry to a new import.
        assertThat(count("route_shapes")).isEqualTo(shapesBefore);
        assertThat(count("stations")).isEqualTo(stationsBefore);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? -1 : value;
    }
}
