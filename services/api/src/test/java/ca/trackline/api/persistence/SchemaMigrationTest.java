package ca.trackline.api.persistence;

import java.util.List;

import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the migrations produce the schema the application depends on.
 *
 * <p>These are not tautologies: an index silently created with the wrong access
 * method, or a missing spatial index, would leave the application functionally
 * correct but unusably slow at Toronto's data volumes, and nothing else in the
 * build would notice.
 */
class SchemaMigrationTest extends PostgisIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationsApplyCleanlyFromAnEmptyDatabase() {
        List<String> applied = jdbc.queryForList(
                "SELECT version || ' ' || description FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);

        assertThat(applied).containsExactly(
                "1 extensions",
                "2 static network",
                "3 ingestion runs",
                "4 shape points",
                "5 scheduled headways");

        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        assertThat(failures).isZero();
    }

    @Test
    void requiredExtensionsArePresent() {
        List<String> extensions = jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('postgis', 'pg_trgm') ORDER BY extname",
                String.class);

        assertThat(extensions).containsExactly("pg_trgm", "postgis");
    }

    @Test
    void everyExpectedTableExists() {
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name NOT IN ('spatial_ref_sys', 'flyway_schema_history')
                ORDER BY table_name
                """,
                String.class);

        assertThat(tables).containsExactly(
                "calendar_exceptions",
                "calendar_services",
                "gtfs_feed_versions",
                "ingestion_runs",
                "route_scheduled_headways",
                "route_shapes",
                "routes",
                "shape_points",
                "stations",
                "stop_times",
                "stops",
                "trips");
    }

    @Test
    void spatialColumnsHaveTheIntendedTypeAndProjection() {
        List<String> geography = jdbc.queryForList(
                """
                SELECT f_table_name || '.' || f_geography_column || ' ' || type || ' ' || srid
                FROM geography_columns WHERE f_table_schema = 'public' ORDER BY 1
                """,
                String.class);

        assertThat(geography).containsExactly(
                "route_shapes.geom_wgs LineString 4326",
                "stations.centroid Point 4326",
                "stops.location Point 4326");

        // Scoped to the public schema: the PostGIS image also ships the tiger
        // geocoder, whose tables appear in these catalog views too.
        // Metre-based projection, because ST_LineLocatePoint and length arithmetic
        // need planar units.
        List<String> geometry = jdbc.queryForList(
                """
                SELECT f_table_name || '.' || f_geometry_column || ' ' || type || ' ' || srid
                FROM geometry_columns WHERE f_table_schema = 'public' ORDER BY 1
                """,
                String.class);

        assertThat(geometry).containsExactly("route_shapes.geom LINESTRING 3857");
    }

    @Test
    void spatialIndexesUseGist() {
        assertThat(indexesUsing("gist")).containsExactly(
                "route_shapes_geom_gix",
                "route_shapes_geom_wgs_gix",
                "stations_centroid_gix",
                "stops_location_gix");
    }

    @Test
    void searchIndexesUseGinTrigram() {
        assertThat(indexesUsing("gin")).containsExactly(
                "routes_search_trgm_idx",
                "stations_name_trgm_idx",
                "stops_name_trgm_idx");

        // The route index spans number and name together, so "501", "Queen" and
        // "501 Queen" all match one index.
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'routes_search_trgm_idx'", String.class);
        assertThat(definition)
                .contains("gin_trgm_ops")
                .contains("short_name")
                .contains("long_name");
    }

    @Test
    void lookupIndexesForTheQueryPathsWeRelyOnExist() {
        List<String> indexes = jdbc.queryForList(
                """
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public' AND indexname IN (
                    'routes_short_name_idx',
                    'trips_route_direction_service_idx',
                    'trips_shape_idx',
                    'stop_times_stop_arrival_idx',
                    'route_shapes_route_direction_idx',
                    'ingestion_runs_feed_started_idx',
                    'stops_station_idx')
                ORDER BY indexname
                """,
                String.class);

        assertThat(indexes).containsExactly(
                "ingestion_runs_feed_started_idx",
                "route_shapes_route_direction_idx",
                "routes_short_name_idx",
                "stop_times_stop_arrival_idx",
                "stops_station_idx",
                "trips_route_direction_service_idx",
                "trips_shape_idx");
    }

    @Test
    void compositePrimaryKeysMatchTheGtfsGrain() {
        assertThat(primaryKeyColumns("stop_times")).containsExactly("trip_id", "stop_sequence");
        assertThat(primaryKeyColumns("calendar_exceptions")).containsExactly("service_id", "exception_date");
    }

    private List<String> indexesUsing(String accessMethod) {
        return jdbc.queryForList(
                """
                SELECT i.relname
                FROM pg_index x
                JOIN pg_class i ON i.oid = x.indexrelid
                JOIN pg_class t ON t.oid = x.indrelid
                JOIN pg_am am ON am.oid = i.relam
                WHERE t.relnamespace = 'public'::regnamespace AND am.amname = ?
                ORDER BY i.relname
                """,
                String.class,
                accessMethod);
    }

    private List<String> primaryKeyColumns(String table) {
        return jdbc.queryForList(
                """
                SELECT a.attname
                FROM pg_index x
                JOIN pg_class t ON t.oid = x.indrelid
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (x.indkey)
                WHERE x.indisprimary AND t.relname = ?
                ORDER BY array_position(x.indkey, a.attnum)
                """,
                String.class,
                table);
    }
}
