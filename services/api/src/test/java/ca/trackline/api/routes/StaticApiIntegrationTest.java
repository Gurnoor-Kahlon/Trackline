package ca.trackline.api.routes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import ca.trackline.api.gtfs.schedule.GtfsImportService;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.persistence.query.SearchQueryRepository;
import ca.trackline.api.persistence.query.StopQueryRepository;
import ca.trackline.api.routes.RouteDtos.RouteStop;
import ca.trackline.api.search.SearchDtos.HitKind;
import ca.trackline.api.search.SearchDtos.SearchHit;
import ca.trackline.api.stops.StopDtos.NearbyStop;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The query layer against real PostGIS and real feed data.
 *
 * <p>These check the things mocks cannot: that the spatial query orders by the
 * distance it reports, that PostGIS emits valid GeoJSON, that trigram ranking puts
 * the right answer first, and that the feed's own units are converted rather than
 * passed through.
 */
class StaticApiIntegrationTest extends PostgisIntegrationTest {

    /** 501 Queen is in the committed fixture, along with route 1 and route 41. */
    private static final String STREETCAR_ROUTE = "501";

    @Autowired
    private GtfsImportService importService;

    @Autowired
    private RouteQueryRepository routes;

    @Autowired
    private StopQueryRepository stops;

    @Autowired
    private SearchQueryRepository search;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

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
        importService.importArchive(fixture, "https://example.test/ttc.zip", "7".repeat(64), System.nanoTime());
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
    void routesAreOrderedByNumberNotByText() {
        List<String> numbers = routes.findRoutes(null, null, 0, 50).stream()
                .map(RouteDtos.RouteSummary::shortName)
                .toList();

        // Text ordering would put 41 after 1 and 501 — riders expect 1, 41, 501.
        assertThat(numbers).containsExactly("1", "41", "501");
    }

    @Test
    void modeFilterSelectsOnRouteType() {
        assertThat(routes.findRoutes(TransitMode.SUBWAY, null, 0, 10))
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.routeId()).isEqualTo("1");
                    // The TTC publishes no subway vehicle positions.
                    assertThat(route.supportsRealtime()).isFalse();
                });

        assertThat(routes.findRoutes(TransitMode.STREETCAR, null, 0, 10))
                .allSatisfy(route -> assertThat(route.supportsRealtime()).isTrue());
    }

    @Test
    void paginationWindowsTheResultsAndTotalCountsThemAll() {
        long total = routes.countRoutes(null, null);
        assertThat(total).isEqualTo(3);

        assertThat(routes.findRoutes(null, null, 0, 2)).hasSize(2);
        assertThat(routes.findRoutes(null, null, 1, 2)).hasSize(1);
        assertThat(routes.findRoutes(null, null, 5, 2)).isEmpty();
    }

    @Test
    void directionsCarryHeadsignsAndPrimaryShapeFacts() {
        List<RouteDtos.RouteDirection> directions = routes.findDirections(STREETCAR_ROUTE);

        assertThat(directions).isNotEmpty();
        assertThat(directions).allSatisfy(direction -> {
            assertThat(direction.tripCount()).isPositive();
            assertThat(direction.headsign()).isNotBlank();
            // Length comes from the primary shape, in true metres.
            assertThat(direction.lengthMetres()).isNotNull().isGreaterThan(1_000.0);
        });
    }

    @Test
    void shapeIsValidGeoJsonAndSimplificationRemovesPointsWithoutChangingLength() {
        var full = routes.findShape(STREETCAR_ROUTE, (short) 0, null).orElseThrow();
        var simplified = routes.findShape(STREETCAR_ROUTE, (short) 0, 25).orElseThrow();

        assertThat(full.geoJson()).contains("\"type\":\"LineString\"").contains("\"coordinates\"");
        assertThat(countCoordinatePairs(simplified.geoJson()))
                .isLessThan(countCoordinatePairs(full.geoJson()));

        // Length is the stored measurement of the real geometry, so simplifying for
        // display must not change what the route is said to be.
        assertThat(simplified.lengthMetres()).isEqualTo(full.lengthMetres());
    }

    @Test
    void routeStopsComeBackInOrderWithDistancesConvertedToMetres() {
        List<RouteStop> routeStops = routes.findStops(STREETCAR_ROUTE, (short) 0);

        assertThat(routeStops).isNotEmpty();
        assertThat(routeStops).extracting(RouteStop::sequence).isSorted();

        Double routeLength = routes.findDirections(STREETCAR_ROUTE).stream()
                .filter(direction -> direction.directionId() == 0)
                .findFirst()
                .orElseThrow()
                .lengthMetres();

        // The feed states this distance in kilometres; measured against the geometry
        // a declared step of 0.0100 spans 10.0 m. Passing it through unconverted
        // would understate every distance a thousandfold, which is only visible by
        // comparing it against the route's own length.
        Double furthest = routeStops.stream()
                .map(RouteStop::distanceAlongRouteMetres)
                .filter(java.util.Objects::nonNull)
                .max(Double::compareTo)
                .orElseThrow();

        // Within the route's own length, because the stops come from a trip running
        // the same shape the geometry endpoint returns. Unconverted kilometres
        // would be a thousand times smaller than this.
        assertThat(furthest)
                .isGreaterThan(routeLength * 0.5)
                .isLessThanOrEqualTo(routeLength * 1.05);
    }

    @Test
    void nearbyOrdersByTheDistanceItReports() {
        // Toronto City Hall, inside the fixture's downtown coverage.
        List<NearbyStop> nearby = stops.findNearby(43.6534, -79.3841, 2_000, 50);

        assertThat(nearby).isNotEmpty();
        assertThat(nearby).extracting(NearbyStop::distanceMetres).isSorted();
        assertThat(nearby).allSatisfy(stop -> assertThat(stop.distanceMetres()).isLessThanOrEqualTo(2_000));
    }

    @Test
    void nearbyRespectsItsRadius() {
        List<NearbyStop> tight = stops.findNearby(43.6534, -79.3841, 200, 50);
        List<NearbyStop> wide = stops.findNearby(43.6534, -79.3841, 2_000, 50);

        assertThat(tight.size()).isLessThanOrEqualTo(wide.size());
        assertThat(tight).allSatisfy(stop -> assertThat(stop.distanceMetres()).isLessThanOrEqualTo(200));
    }

    @Test
    void nearbyReturnsNothingRatherThanTheNearestStopWhenNothingIsClose() {
        // Far out in Lake Ontario. An empty list is the honest answer; a KNN query
        // with no radius would cheerfully return a stop kilometres away.
        assertThat(stops.findNearby(43.3, -79.2, 500, 10)).isEmpty();
    }

    @Test
    void stopDetailListsTheRoutesThatCallThere() {
        String stopId = routes.findStops(STREETCAR_ROUTE, (short) 0).getFirst().stopId();

        assertThat(stops.findStop(stopId)).isPresent();
        assertThat(stops.findRoutesServing(stopId))
                .extracting(RouteDtos.RouteSummary::routeId)
                .contains(STREETCAR_ROUTE);
    }

    @Test
    void searchPutsAnExactRouteNumberFirst() {
        List<SearchHit> hits = search.search("501", 10);

        // Trigram similarity alone ranks this badly: "501" against "501 Queen"
        // scores low, and a stop whose name contains 501 would outrank the route.
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().kind()).isEqualTo(HitKind.ROUTE);
        assertThat(hits.getFirst().id()).isEqualTo("501");
        assertThat(hits.getFirst().score()).isEqualTo(1.0);
    }

    @Test
    void searchFindsRoutesByName() {
        assertThat(search.search("Queen", 10))
                .anySatisfy(hit -> {
                    assertThat(hit.kind()).isEqualTo(HitKind.ROUTE);
                    assertThat(hit.id()).isEqualTo("501");
                });
    }

    @Test
    void searchToleratesANearMiss() {
        // A rider typing quickly still finds the route: trigram matching is the
        // reason search does not need an exact spelling.
        assertThat(search.search("Quen", 20))
                .isNotEmpty()
                .anySatisfy(hit -> assertThat(hit.sublabel()).containsIgnoringCase("queen"));
    }

    @Test
    void searchReturnsNothingForATermThatMatchesNothing() {
        assertThat(search.search("zzzzqqqq", 10)).isEmpty();
    }

    @Test
    void searchResultsComeBackBestFirst() {
        List<Double> scores = search.search("Queen", 20).stream().map(SearchHit::score).toList();

        assertThat(scores).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    private static int countCoordinatePairs(String geoJson) {
        return geoJson.split("\\],\\[").length;
    }
}
