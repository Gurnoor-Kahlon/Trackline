package ca.trackline.api.gtfs.schedule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Imports a committed subset of the real TTC feed into real PostGIS.
 *
 * <p>The fixture is genuine feed data rather than invented rows, so it carries
 * the quirks that matter: a byte-order mark, times past 24:00:00, and empty
 * shape_dist_traveled fields.
 */
class GtfsImportIntegrationTest extends PostgisIntegrationTest {

    /** Counts in the committed fixture, verified when it was built. */
    private static final int ROUTES = 3;
    private static final int STOPS = 157;
    private static final int TRIPS = 12;
    private static final int STOP_TIMES = 608;
    private static final int SHAPE_POINTS = 2819;
    private static final int CALENDAR_SERVICES = 1;
    private static final int CALENDAR_EXCEPTIONS = 1;

    @Autowired
    private GtfsImportService importService;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private Path fixture;

    @AfterEach
    void clearWhatThisTestCommitted() {
        // This test commits, and the PostGIS container is shared with every other
        // integration test. Leaving rows behind would collide with their fixtures.
        truncateNetwork();
        jdbc.execute("DELETE FROM gtfs_feed_versions");
    }

    @BeforeEach
    void copyFixtureToDisk() throws IOException {
        fixture = tempDir.resolve("ttc-subset.zip");
        try (InputStream source = requireFixture()) {
            Files.copy(source, fixture);
        }
        truncateNetwork();
        jdbc.execute("DELETE FROM gtfs_feed_versions");
    }

    private void truncateNetwork() {
        jdbc.execute("""
                TRUNCATE stop_times, shape_points, trips, calendar_exceptions,
                         calendar_services, route_shapes, stops, stations, routes
                """);
    }

    private static InputStream requireFixture() {
        InputStream stream = GtfsImportIntegrationTest.class.getResourceAsStream("/gtfs/ttc-subset.zip");
        if (stream == null) {
            throw new IllegalStateException("Test fixture /gtfs/ttc-subset.zip is missing");
        }
        return stream;
    }

    private GtfsImportResult importFixture() {
        return importFixture("a".repeat(64));
    }

    private GtfsImportResult importFixture(String sha256) {
        return importService.importArchive(fixture, "https://example.test/ttc.zip", sha256, System.nanoTime());
    }

    @Test
    void importsEveryFileWithTheExpectedRowCounts() {
        GtfsImportResult result = importFixture();

        assertThat(result.status()).isEqualTo(GtfsImportStatus.IMPORTED);
        assertThat(result.rowCounts())
                .containsEntry("routes", ROUTES)
                .containsEntry("stops", STOPS)
                .containsEntry("trips", TRIPS)
                .containsEntry("stop_times", STOP_TIMES)
                .containsEntry("shape_points", SHAPE_POINTS)
                .containsEntry("calendar_services", CALENDAR_SERVICES)
                .containsEntry("calendar_exceptions", CALENDAR_EXCEPTIONS);

        assertThat(count("routes")).isEqualTo(ROUTES);
        assertThat(count("stops")).isEqualTo(STOPS);
        assertThat(count("trips")).isEqualTo(TRIPS);
        assertThat(count("stop_times")).isEqualTo(STOP_TIMES);
        assertThat(count("shape_points")).isEqualTo(SHAPE_POINTS);
    }

    @Test
    void marksTheImportedVersionActiveWithItsServiceWindow() {
        GtfsImportResult result = importFixture();

        String status = jdbc.queryForObject(
                "SELECT status FROM gtfs_feed_versions WHERE id = ?", String.class, result.feedVersionId());
        assertThat(status).isEqualTo("ACTIVE");

        LocalDate start = jdbc.queryForObject(
                "SELECT feed_start_date FROM gtfs_feed_versions WHERE id = ?", LocalDate.class, result.feedVersionId());
        LocalDate end = jdbc.queryForObject(
                "SELECT feed_end_date FROM gtfs_feed_versions WHERE id = ?", LocalDate.class, result.feedVersionId());

        // The window comes from the calendar that was actually loaded, which is
        // what later milestones check before trusting schedule-derived numbers.
        assertThat(start).isNotNull();
        assertThat(end).isNotNull().isAfterOrEqualTo(start);
        assertThat(result.feedStartDate()).isEqualTo(start);

        String rowCounts = jdbc.queryForObject(
                "SELECT row_counts::text FROM gtfs_feed_versions WHERE id = ?", String.class, result.feedVersionId());
        assertThat(rowCounts).contains("\"stop_times\": " + STOP_TIMES);
    }

    @Test
    void storesTimesPastMidnightAsSecondsBeyondADay() {
        importFixture();

        Integer lateCalls = jdbc.queryForObject(
                "SELECT count(*) FROM stop_times WHERE arrival_seconds >= 86400", Integer.class);

        // Real late-night trips from the feed. Parsed as a time of day these would
        // have been rejected, or wrapped onto the wrong service day.
        assertThat(lateCalls).isPositive();

        Integer maximum = jdbc.queryForObject("SELECT max(arrival_seconds) FROM stop_times", Integer.class);
        assertThat(maximum).isGreaterThan(86_400);
    }

    @Test
    void keepsAbsentShapeDistanceAsNullRatherThanZero() {
        importFixture();

        Integer missing = jdbc.queryForObject(
                "SELECT count(*) FROM stop_times WHERE shape_dist_traveled IS NULL", Integer.class);

        // Defaulting these to 0 would claim a measurement the feed never gave.
        assertThat(missing).isPositive();
    }

    @Test
    void stopLocationsBecomeUsableGeography() {
        importFixture();

        Integer withoutLocation = jdbc.queryForObject(
                "SELECT count(*) FROM stops WHERE location IS NULL", Integer.class);
        assertThat(withoutLocation).isZero();

        // Not merely stored: PostGIS must be able to measure with it, in metres.
        Double nearest = jdbc.queryForObject(
                """
                SELECT min(ST_Distance(location, ST_MakePoint(-79.3832, 43.6532)::geography))
                FROM stops
                """,
                Double.class);
        assertThat(nearest).isNotNull().isLessThan(50_000.0);

        Integer outsideToronto = jdbc.queryForObject(
                """
                SELECT count(*) FROM stops
                WHERE ST_Y(location::geometry) NOT BETWEEN 43.0 AND 44.5
                   OR ST_X(location::geometry) NOT BETWEEN -80.0 AND -78.5
                """,
                Integer.class);
        assertThat(outsideToronto).isZero();
    }

    @Test
    void derivesRealtimeSupportFromTheMode() {
        importFixture();

        Boolean subway = jdbc.queryForObject(
                "SELECT supports_realtime FROM routes WHERE route_id = '1'", Boolean.class);
        Boolean streetcar = jdbc.queryForObject(
                "SELECT supports_realtime FROM routes WHERE route_id = '501'", Boolean.class);

        // The TTC publishes no subway vehicle positions.
        assertThat(subway).isFalse();
        assertThat(streetcar).isTrue();
    }

    @Test
    void reimportingReplacesTheNetworkWithoutDuplicating() {
        importFixture("a".repeat(64));
        // A different digest: the same archive republished unchanged never reaches
        // this path, it is short-circuited as UNCHANGED before any loading.
        GtfsImportResult second = importFixture("d".repeat(64));

        assertThat(second.status()).isEqualTo(GtfsImportStatus.IMPORTED);
        assertThat(count("stop_times")).isEqualTo(STOP_TIMES);
        assertThat(count("routes")).isEqualTo(ROUTES);

        // Exactly one network is live, enforced by the database.
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM gtfs_feed_versions WHERE status = 'ACTIVE'", Integer.class);
        assertThat(active).isEqualTo(1);

        Integer superseded = jdbc.queryForObject(
                "SELECT count(*) FROM gtfs_feed_versions WHERE status = 'SUPERSEDED'", Integer.class);
        assertThat(superseded).isEqualTo(1);
    }

    @Test
    void aFailedImportLeavesThePreviousNetworkUntouched() throws IOException {
        GtfsImportResult first = importFixture();
        Path corrupted = corruptStopTimes();

        assertThatThrownBy(() -> importService.importArchive(
                        corrupted, "https://example.test/bad.zip", "b".repeat(64), System.nanoTime()))
                .isInstanceOf(GtfsFormatException.class);

        // Everything the good import loaded is still present and still active: a
        // feed that will not parse must never leave a half-replaced network.
        assertThat(count("stop_times")).isEqualTo(STOP_TIMES);
        assertThat(count("routes")).isEqualTo(ROUTES);

        Long activeId = jdbc.queryForObject(
                "SELECT id FROM gtfs_feed_versions WHERE status = 'ACTIVE'", Long.class);
        assertThat(activeId).isEqualTo(first.feedVersionId());
    }

    @Test
    void anArchiveMissingARequiredFileIsRejectedBeforeAnythingIsWritten() throws IOException {
        importFixture();
        Path withoutTrips = withoutEntry("trips.txt");

        assertThatThrownBy(() -> importService.importArchive(
                        withoutTrips, "https://example.test/partial.zip", "c".repeat(64), System.nanoTime()))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("trips.txt");

        assertThat(count("trips")).isEqualTo(TRIPS);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? -1 : value;
    }

    /** The fixture with one unparseable arrival_time, leaving the rest valid. */
    private Path corruptStopTimes() throws IOException {
        return rewriteFixture("corrupt.zip", (name, bytes) -> {
            if (!name.equals("stop_times.txt")) {
                return bytes;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            int firstRow = text.indexOf('\n') + 1;
            int secondRow = text.indexOf('\n', firstRow) + 1;
            String broken = text.substring(0, firstRow)
                    + text.substring(firstRow, secondRow).replaceFirst(",\\d+:\\d+:\\d+,", ",not-a-time,")
                    + text.substring(secondRow);
            return broken.getBytes(StandardCharsets.UTF_8);
        });
    }

    private Path withoutEntry(String omitted) throws IOException {
        return rewriteFixture("partial.zip", (name, bytes) -> name.equals(omitted) ? null : bytes);
    }

    private interface EntryRewriter {
        byte[] apply(String name, byte[] content);
    }

    private Path rewriteFixture(String fileName, EntryRewriter rewriter) throws IOException {
        Path target = tempDir.resolve(fileName);
        try (ZipFile source = new ZipFile(fixture.toFile());
                ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {

            List<? extends ZipEntry> entries = source.stream().toList();
            for (ZipEntry entry : entries) {
                byte[] content;
                try (InputStream stream = source.getInputStream(entry)) {
                    content = stream.readAllBytes();
                }
                byte[] rewritten = rewriter.apply(entry.getName(), content);
                if (rewritten == null) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(rewritten);
                out.closeEntry();
            }
        }
        return target;
    }
}
