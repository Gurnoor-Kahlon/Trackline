package ca.trackline.api.gtfs.schedule;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import ca.trackline.api.gtfs.schedule.derive.NetworkDerivation;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Imports the GTFS static feed into the database.
 *
 * <p>The whole load runs in one transaction that ends by flipping the new feed
 * version to ACTIVE. Anything that fails on the way rolls the transaction back,
 * so a failed import leaves the previously active network exactly as it was
 * rather than half-replacing it.
 *
 * <p>Only one import may run at a time. Two concurrent imports would each try to
 * replace the network under the other.
 */
@Service
public class GtfsImportService {

    private static final Logger log = LoggerFactory.getLogger(GtfsImportService.class);

    /**
     * Cleared before every import. Listed child-first so foreign keys are
     * satisfied; stations go too because they are derived from stops and would
     * otherwise describe a network that no longer exists.
     */
    private static final String TRUNCATE_NETWORK = """
            TRUNCATE stop_times, shape_points, trips, calendar_exceptions,
                     calendar_services, route_scheduled_headways, route_shapes,
                     stops, stations, routes
            """;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final GtfsFeedResolver resolver;
    private final GtfsFeedDownloader downloader;
    private final GtfsStaticProperties properties;
    private final NetworkDerivation derivation;
    private final AtomicBoolean running = new AtomicBoolean();

    public GtfsImportService(
            DataSource dataSource,
            JdbcTemplate jdbc,
            GtfsFeedResolver resolver,
            GtfsFeedDownloader downloader,
            GtfsStaticProperties properties,
            NetworkDerivation derivation) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.downloader = downloader;
        this.properties = properties;
        this.derivation = derivation;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Resolves, downloads and imports the current feed.
     *
     * @throws IllegalStateException if an import is already running
     */
    public GtfsImportResult importLatest() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A GTFS import is already running");
        }

        long started = System.nanoTime();
        Path archiveFile = null;
        String sha256 = null;

        try {
            GtfsFeedResource resource = resolver.resolve();
            archiveFile = Files.createTempFile("trackline-gtfs-", ".zip");
            sha256 = downloader.downloadTo(resource, archiveFile);

            Long activeId = activeVersionIdFor(sha256);
            if (activeId != null) {
                log.info("GTFS archive sha256={} is already the active version; nothing to do", sha256);
                return GtfsImportResult.unchanged(activeId, sha256, elapsedMs(started));
            }

            return importArchive(archiveFile, resource.downloadUrl(), sha256, started);
        } catch (IOException | RuntimeException failure) {
            log.error("GTFS import failed", failure);
            return GtfsImportResult.failed(sha256, shortReason(failure), elapsedMs(started));
        } finally {
            deleteQuietly(archiveFile);
            running.set(false);
        }
    }

    /**
     * Imports an archive already on disk. Exposed for tests, which use a committed
     * subset rather than downloading 36 MB.
     */
    public GtfsImportResult importArchive(Path archiveFile, String sourceUrl, String sha256, long startedNanos) {
        try (GtfsArchive archive =
                GtfsArchive.open(archiveFile, properties.maxEntries(), properties.maxEntryBytes())) {

            List<GtfsTableLoader> loaders = GtfsTableLoaders.inDependencyOrder();
            requirePresent(archive, loaders);

            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    long feedVersionId = insertImportingVersion(connection, sha256, sourceUrl);
                    execute(connection, TRUNCATE_NETWORK);

                    Map<String, Integer> rowCounts = new LinkedHashMap<>();
                    for (GtfsTableLoader loader : loaders) {
                        if (!archive.contains(loader.file())) {
                            continue;
                        }
                        rowCounts.put(loader.table(), (int) load(connection, archive, loader, feedVersionId));
                    }

                    LocalDate[] window = serviceWindow(connection);

                    // Derived inside the same transaction: an active network with
                    // no geometry could not place a vehicle on a route or draw a
                    // map, so the two become visible together or not at all.
                    NetworkDerivation.DerivationSummary derived =
                            derivation.derive(connection, feedVersionId, window[0], window[1]);
                    rowCounts.put("route_shapes", derived.shapes());
                    rowCounts.put("stations", derived.stations());
                    rowCounts.put("route_scheduled_headways", derived.headwayEntries());

                    activate(connection, feedVersionId, rowCounts, window[0], window[1]);

                    connection.commit();
                    long durationMs = elapsedMs(startedNanos);
                    log.info(
                            "GTFS import complete versionId={} durationMs={} rows={}",
                            feedVersionId, durationMs, rowCounts);
                    return GtfsImportResult.imported(
                            feedVersionId, sha256, rowCounts, window[0], window[1], durationMs);
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException("GTFS import failed", failure);
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            }
        } catch (IOException | SQLException cause) {
            throw new IllegalStateException("GTFS import failed", cause);
        }
    }

    private static void requirePresent(GtfsArchive archive, List<GtfsTableLoader> loaders) {
        for (GtfsTableLoader loader : loaders) {
            if (loader.required() && !archive.contains(loader.file())) {
                throw new GtfsFormatException("Archive is missing required file '%s'".formatted(loader.file()));
            }
        }
    }

    /**
     * Streams one file through COPY.
     *
     * <p>Rows are transformed one at a time and never collected, so memory use is
     * flat whether the file has 236 rows or 4.36 million.
     */
    private long load(Connection connection, GtfsArchive archive, GtfsTableLoader loader, long feedVersionId)
            throws SQLException, IOException {
        long started = System.nanoTime();
        CopyIn copyIn = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(loader.copyStatement());
        CopyRowWriter writer = new CopyRowWriter(copyIn, properties.copyBatchBytes());

        try (BufferedReader reader = archive.reader(loader.file());
                CSVParser parser = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreSurroundingSpaces(true)
                        .get()
                        .parse(reader)) {

            for (CSVRecord record : parser) {
                loader.writeRow(record, feedVersionId, writer);
            }
            writer.close();
        } catch (RuntimeException | IOException failure) {
            writer.cancel();
            throw failure;
        }

        long rows = writer.rowCount();
        log.info(
                "Loaded {} rows into {} in {} ms",
                rows, loader.table(), (System.nanoTime() - started) / 1_000_000);
        return rows;
    }

    private Long activeVersionIdFor(String sha256) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM gtfs_feed_versions WHERE sha256 = ? AND status = 'ACTIVE'",
                Long.class,
                sha256);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long insertImportingVersion(Connection connection, String sha256, String sourceUrl) throws SQLException {
        // Any earlier attempt left mid-flight is closed out first, so the partial
        // unique index on ACTIVE cannot collide with this import.
        try (var statement = connection.prepareStatement(
                "UPDATE gtfs_feed_versions SET status = 'FAILED' WHERE status = 'IMPORTING'")) {
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement(
                """
                INSERT INTO gtfs_feed_versions (sha256, source_url, downloaded_at, status)
                VALUES (?, ?, ?, 'IMPORTING')
                RETURNING id
                """)) {
            statement.setString(1, sha256);
            statement.setString(2, sourceUrl);
            statement.setObject(3, java.time.OffsetDateTime.now());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Inserting the feed version returned no id");
                }
                return rows.getLong(1);
            }
        }
    }

    /** The span of service days the imported calendar actually covers. */
    private LocalDate[] serviceWindow(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT min(start_date), max(end_date) FROM calendar_services")) {
            if (rows.next()) {
                java.sql.Date start = rows.getDate(1);
                java.sql.Date end = rows.getDate(2);
                return new LocalDate[] {
                    start == null ? null : start.toLocalDate(),
                    end == null ? null : end.toLocalDate()
                };
            }
            return new LocalDate[] {null, null};
        }
    }

    private void activate(
            Connection connection,
            long feedVersionId,
            Map<String, Integer> rowCounts,
            LocalDate start,
            LocalDate end) throws SQLException {

        try (var statement = connection.prepareStatement(
                "UPDATE gtfs_feed_versions SET status = 'SUPERSEDED' WHERE status = 'ACTIVE' AND id <> ?")) {
            statement.setLong(1, feedVersionId);
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement(
                """
                UPDATE gtfs_feed_versions
                SET status = 'ACTIVE', imported_at = ?, feed_start_date = ?, feed_end_date = ?,
                    row_counts = ?::jsonb
                WHERE id = ?
                """)) {
            statement.setObject(1, java.time.OffsetDateTime.now());
            statement.setObject(2, start);
            statement.setObject(3, end);
            statement.setString(4, toJson(rowCounts));
            statement.setLong(5, feedVersionId);
            statement.executeUpdate();
        }
    }

    private static String toJson(Map<String, Integer> rowCounts) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : rowCounts.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String shortReason(Throwable failure) {
        String message = failure.getMessage();
        String text = message == null ? failure.getClass().getSimpleName() : message;
        return text.length() <= 300 ? text : text.substring(0, 297) + "...";
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException cause) {
            log.warn("Could not delete temporary archive {}", file, cause);
        }
    }
}
