package ca.trackline.api.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import ca.trackline.api.persistence.entity.FeedVersionStatus;
import ca.trackline.api.persistence.entity.GtfsFeedVersionEntity;
import ca.trackline.api.persistence.entity.RouteEntity;
import ca.trackline.api.persistence.entity.RouteShapeEntity;
import ca.trackline.api.persistence.entity.StopEntity;
import ca.trackline.api.persistence.repository.GtfsFeedVersionRepository;
import ca.trackline.api.persistence.repository.RouteRepository;
import ca.trackline.api.persistence.repository.RouteShapeRepository;
import ca.trackline.api.persistence.repository.StopRepository;
import org.junit.jupiter.api.BeforeEach;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that geometry written through JPA comes back intact and is usable by
 * PostGIS, and that the entity mappings agree with the migrated schema.
 *
 * <p>Round-tripping matters because the projection maths the whole analytics layer
 * depends on is only valid if coordinates and SRIDs survive persistence exactly.
 */
@Transactional
class SpatialMappingTest extends PostgisIntegrationTest {

    /** Real TTC coordinates, so a wrong SRID or flipped axis produces obvious nonsense. */
    private static final double DANFORTH_AT_KENNEDY_LAT = 43.714379;
    private static final double DANFORTH_AT_KENNEDY_LON = -79.260939;

    private static final GeometryFactory WGS84 =
            new GeometryFactory(new PrecisionModel(), 4326);
    private static final GeometryFactory WEB_MERCATOR =
            new GeometryFactory(new PrecisionModel(), 3857);

    @Autowired
    private GtfsFeedVersionRepository feedVersions;

    @Autowired
    private RouteRepository routes;

    @Autowired
    private StopRepository stops;

    @Autowired
    private RouteShapeRepository routeShapes;

    @Autowired
    private JdbcTemplate jdbc;

    private Long feedVersionId;

    @BeforeEach
    void importedFeedVersionExists() {
        GtfsFeedVersionEntity version = new GtfsFeedVersionEntity(
                "a".repeat(64), "https://example.test/gtfs.zip", Instant.parse("2026-09-25T18:08:04Z"),
                FeedVersionStatus.IMPORTING);
        version.setFeedStartDate(LocalDate.of(2026, 9, 30));
        version.setFeedEndDate(LocalDate.of(2026, 10, 31));
        version.setRowCounts(Map.of("routes", 236, "stops", 9409));
        feedVersionId = feedVersions.saveAndFlush(version).getId();
    }

    @Test
    void geographyPointSurvivesARoundTripAndPostgisCanMeasureIt() {
        Point location = WGS84.createPoint(
                new Coordinate(DANFORTH_AT_KENNEDY_LON, DANFORTH_AT_KENNEDY_LAT));
        stops.saveAndFlush(new StopEntity(
                "662", feedVersionId, "662", "Danforth Rd at Kennedy Rd", location, (short) 1));

        StopEntity reloaded = stops.findById("662").orElseThrow();

        assertThat(reloaded.getLocation().getX()).isEqualTo(DANFORTH_AT_KENNEDY_LON);
        assertThat(reloaded.getLocation().getY()).isEqualTo(DANFORTH_AT_KENNEDY_LAT);
        assertThat(reloaded.getLocation().getSRID()).isEqualTo(4326);
        assertThat(reloaded.getWheelchairBoarding()).isEqualTo((short) 1);

        // Metres on the spheroid, not degrees: a geography column is only worth
        // having if distance comes back in real units.
        Double metresFromUnionStation = jdbc.queryForObject(
                "SELECT ST_Distance(location, ST_MakePoint(-79.3806, 43.6453)::geography) FROM stops WHERE stop_id = '662'",
                Double.class);
        assertThat(metresFromUnionStation).isBetween(11_000.0, 13_000.0);
    }

    @Test
    void lineStringSurvivesARoundTripAndSupportsProgressProjection() {
        routes.saveAndFlush(route("501", "Queen"));

        // A 400 m eastward line in Web Mercator metres.
        LineString metres = WEB_MERCATOR.createLineString(new Coordinate[] {
                new Coordinate(-8_837_000, 5_411_000),
                new Coordinate(-8_836_600, 5_411_000),
        });
        LineString degrees = WGS84.createLineString(new Coordinate[] {
                new Coordinate(-79.3832, 43.6532),
                new Coordinate(-79.3796, 43.6532),
        });

        RouteShapeEntity shape = new RouteShapeEntity(
                "1121835", feedVersionId, "501", (short) 0, metres, degrees,
                new BigDecimal("400.00"), 42);
        shape.setPrimary(true);
        routeShapes.saveAndFlush(shape);

        RouteShapeEntity reloaded = routeShapes.findById("1121835").orElseThrow();
        assertThat(reloaded.getGeom().getNumPoints()).isEqualTo(2);
        assertThat(reloaded.getGeom().getSRID()).isEqualTo(3857);
        assertThat(reloaded.getGeomWgs().getSRID()).isEqualTo(4326);
        assertThat(reloaded.isPrimary()).isTrue();
        assertThat(reloaded.isLoop()).isFalse();

        // The operation vehicle progress along a route is built on: a point a
        // quarter of the way along must locate at 0.25.
        Double fraction = jdbc.queryForObject(
                """
                SELECT ST_LineLocatePoint(geom, ST_SetSRID(ST_MakePoint(-8836900, 5411000), 3857))
                FROM route_shapes WHERE shape_id = '1121835'
                """,
                Double.class);
        assertThat(fraction).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.0001));

        Double lengthMetres = jdbc.queryForObject(
                "SELECT ST_Length(geom) FROM route_shapes WHERE shape_id = '1121835'", Double.class);
        assertThat(lengthMetres).isCloseTo(400.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void jsonRowCountsAndFeedWindowRoundTrip() {
        GtfsFeedVersionEntity reloaded = feedVersions.findById(feedVersionId).orElseThrow();

        assertThat(reloaded.getRowCounts()).containsEntry("routes", 236).containsEntry("stops", 9409);
        assertThat(reloaded.getFeedStartDate()).isEqualTo(LocalDate.of(2026, 9, 30));

        // The feed Trackline downloaded on 2026-09-25 described a service period
        // starting 2026-09-30, so it did not cover that day. Schedule-derived
        // features must be able to detect exactly this.
        assertThat(reloaded.covers(LocalDate.of(2026, 9, 27))).isFalse();
        assertThat(reloaded.covers(LocalDate.of(2026, 10, 1))).isTrue();
    }

    @Test
    void onlyOneFeedVersionMayBeActive() {
        GtfsFeedVersionEntity first = feedVersions.findById(feedVersionId).orElseThrow();
        first.setStatus(FeedVersionStatus.ACTIVE);
        feedVersions.saveAndFlush(first);

        GtfsFeedVersionEntity second = new GtfsFeedVersionEntity(
                "b".repeat(64), "https://example.test/gtfs.zip", Instant.now(), FeedVersionStatus.ACTIVE);

        // Which network is live must never be ambiguous, so the database refuses a
        // second ACTIVE version rather than trusting import code to be careful.
        assertThatThrownBy(() -> feedVersions.saveAndFlush(second))
                .hasMessageContaining("gtfs_feed_versions_one_active_idx");
    }

    @Test
    void accessibilityValuesOutsideTheGtfsRangeAreRejected() {
        Point location = WGS84.createPoint(new Coordinate(DANFORTH_AT_KENNEDY_LON, DANFORTH_AT_KENNEDY_LAT));

        // 3 is not a GTFS wheelchair_boarding value; accepting it would mean
        // displaying accessibility information the feed never stated.
        assertThatThrownBy(() -> stops.saveAndFlush(new StopEntity(
                        "999", feedVersionId, "999", "Invented Stop", location, (short) 3)))
                .hasMessageContaining("stops_wheelchair_boarding_check");
    }

    @Test
    void aStopMustReferenceAFeedVersionThatExists() {
        Point location = WGS84.createPoint(new Coordinate(DANFORTH_AT_KENNEDY_LON, DANFORTH_AT_KENNEDY_LAT));

        assertThatThrownBy(() -> stops.saveAndFlush(new StopEntity(
                        "998", 999_999L, "998", "Orphan Stop", location, (short) 0)))
                .hasMessageContaining("stops_feed_version_id_fkey");
    }

    private RouteEntity route(String routeId, String longName) {
        return new RouteEntity(
                routeId, feedVersionId, routeId, longName, (short) 0, "DA251D", "FFFFFF", true);
    }
}
