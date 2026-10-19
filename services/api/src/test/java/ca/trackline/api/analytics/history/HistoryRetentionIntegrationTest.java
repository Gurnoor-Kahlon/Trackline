package ca.trackline.api.analytics.history;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import ca.trackline.api.analytics.history.HistoryRetention.Report;
import ca.trackline.api.persistence.partition.DailyPartitions;
import ca.trackline.api.persistence.partition.DailyPartitions.Partition;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention against a real database: what goes, what stays, and that one failing step does not stop the rest.
 *
 * <p>Every date here is in early 2026, before any other test's data, so trimming to "now" as of February
 * never reaches rows another test class left in the shared database.
 */
class HistoryRetentionIntegrationTest extends PostgisIntegrationTest {

    /** Retention is 90 days for samples, so their cutoff is 22 November 2025, midday. */
    private static final Instant NOW = Instant.parse("2026-02-20T12:00:00Z");

    private static final String ROUTE = "RET";

    @Autowired
    private HistoryRetention retention;

    @Autowired
    private DailyPartitions partitions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final List<Long> runs = new ArrayList<>();

    @BeforeEach
    void clean() {
        remove();
    }

    @AfterEach
    void remove() {
        jdbc.update("DELETE FROM vehicle_observations WHERE vehicle_id LIKE 'ret-%'");
        runs.forEach(id -> jdbc.update("DELETE FROM ingestion_runs WHERE id = ?", id));
        runs.clear();
        jdbc.update("DELETE FROM bunching_events WHERE route_id = ?", ROUTE);
        jdbc.update("DELETE FROM service_alerts WHERE source_alert_id LIKE 'ret-%'");
        jdbc.update("DELETE FROM route_headway_hourly WHERE route_id = ?", ROUTE);
        for (String table : List.of(HealthSampler.TABLE, "vehicle_observations")) {
            for (Partition partition : partitions.list(table)) {
                if (partition.name().matches(".*_(2025|2026)_(0[1-2]|11|12)_\\d\\d")) {
                    partitions.drop(table, partition, Duration.ofSeconds(2));
                }
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private void day(String table, String date) {
        partitions.ensure(table, LocalDate.parse(date));
    }

    private void sample(String at) {
        jdbc.update(
                """
                INSERT INTO route_health_samples (route_id, direction_id, sampled_at, state, confidence, reason_codes, vehicles)
                VALUES (?, 0, ?::timestamptz, 'NORMAL', 'HIGH', '{}', 5)
                """,
                ROUTE, at);
    }

    private long run(String startedAt) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO ingestion_runs (feed, started_at, duration_ms, status, entity_count)
                VALUES ('VEHICLE_POSITIONS', ?::timestamptz, 1, 'SUCCESS', 1) RETURNING id
                """,
                Long.class, startedAt);
        runs.add(id);
        return id;
    }

    private void observation(String vehicle, String at, long runId) {
        jdbc.update(
                """
                INSERT INTO vehicle_observations (vehicle_id, source_timestamp, observed_at, route_id, position, ingestion_run_id)
                VALUES (?, ?::timestamptz, ?::timestamptz, ?, ST_SetSRID(ST_MakePoint(-79.38, 43.65), 4326)::geography, ?)
                """,
                vehicle, at, at, ROUTE, runId);
    }

    private void event(String firstSeen, String resolved) {
        jdbc.update(
                """
                INSERT INTO bunching_events (
                    route_id, direction_id, vehicle_ids, peak_vehicles, span_m, expected_gap_m, service_gap_m,
                    basis, severity, peak_severity, sightings, first_seen_at, last_seen_at, resolved_at)
                VALUES (?, 0, ARRAY['a', 'b'], 2, 100, 1000, 2500, 'SCHEDULE', 'MODERATE', 'MODERATE', 3,
                        ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                ROUTE, firstSeen, firstSeen, resolved);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE route_id = '" + ROUTE + "'", Integer.class);
    }

    private List<String> names(String table) {
        return partitions.list(table).stream().map(Partition::name).filter(name -> name.matches(".*_(2025|2026)_(0[1-2]|11|12)_\\d\\d")).toList();
    }

    // --- partitions -------------------------------------------------------------------------------------------

    @Test
    void samplePartitionsThatHaveWhollyEndedBeforeTheCutoffAreDroppedAndTheRestKept() {
        for (String date : List.of("2025-11-20", "2025-11-21", "2025-11-22", "2025-11-23")) {
            day(HealthSampler.TABLE, date);
        }
        sample("2025-11-20T10:00:00Z");
        sample("2025-11-21T23:00:00Z");
        // The cutoff is 12:00 on the 22nd. This partition holds noon to midnight inside the window and
        // must survive, even though half of what it covers is older than the window.
        sample("2025-11-22T13:00:00Z");
        sample("2025-11-23T01:00:00Z");

        Report report = retention.run(NOW);

        assertThat(report.partitionsDropped())
                .containsExactlyInAnyOrder("route_health_samples_2025_11_20", "route_health_samples_2025_11_21");
        assertThat(names(HealthSampler.TABLE))
                .containsExactly("route_health_samples_2025_11_22", "route_health_samples_2025_11_23");
        assertThat(count("route_health_samples")).isEqualTo(2);
        assertThat(report.failedSteps()).isEmpty();
    }

    @Test
    void aPartitionEndingExactlyAtTheCutoffIsDroppedAndOneEndingAfterItIsNot() {
        day(HealthSampler.TABLE, "2025-11-21");
        day(HealthSampler.TABLE, "2025-11-22");

        // Midnight on the 22nd is when the 21st ends: nothing in it is inside the window.
        Report report = retention.run(Instant.parse("2026-02-20T00:00:00Z"));

        assertThat(report.partitionsDropped()).containsExactly("route_health_samples_2025_11_21");
        assertThat(names(HealthSampler.TABLE)).containsExactly("route_health_samples_2025_11_22");
    }

    @Test
    void storedPositionsAreTrimmedAtTheirOwnShorterRetention() {
        long recent = run("2026-02-19T00:00:00Z");
        for (String date : List.of("2026-02-04", "2026-02-05", "2026-02-06", "2026-02-07")) {
            day("vehicle_observations", date);
        }
        observation("ret-a", "2026-02-04T10:00:00Z", recent);
        observation("ret-b", "2026-02-05T10:00:00Z", recent);
        observation("ret-c", "2026-02-06T13:00:00Z", recent);
        observation("ret-d", "2026-02-07T10:00:00Z", recent);

        Report report = retention.run(NOW);

        assertThat(report.partitionsDropped())
                .containsExactlyInAnyOrder("vehicle_observations_2026_02_04", "vehicle_observations_2026_02_05");
        assertThat(jdbc.queryForList("SELECT vehicle_id FROM vehicle_observations WHERE vehicle_id LIKE 'ret-%' ORDER BY 1", String.class))
                .containsExactly("ret-c", "ret-d");
    }

    @Test
    void aPartitionThatCannotBeDroppedForALockIsReportedAndTheOthersStillGo() throws Exception {
        day(HealthSampler.TABLE, "2025-11-19");
        day(HealthSampler.TABLE, "2025-11-20");

        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            try (var statement = reader.createStatement()) {
                statement.execute("LOCK TABLE route_health_samples_2025_11_19 IN ACCESS SHARE MODE");

                Report first = retention.run(NOW);

                assertThat(first.failedSteps()).containsExactly("route_health_samples_2025_11_19");
                assertThat(first.partitionsDropped()).containsExactly("route_health_samples_2025_11_20");
            } finally {
                reader.rollback();
            }
        }

        // Nothing was lost by failing: the next run, with the lock gone, removes it.
        Report second = retention.run(NOW);
        assertThat(second.failedSteps()).isEmpty();
        assertThat(second.partitionsDropped()).containsExactly("route_health_samples_2025_11_19");
    }

    // --- smaller tables ---------------------------------------------------------------------------------------

    @Test
    void resolvedBunchingEventsPastTheirRetentionAreDeletedAndOpenOnesNever() {
        // 180 days before NOW is 24 August 2025.
        event("2025-08-01T10:00:00Z", "2025-08-01T10:30:00Z");
        event("2025-12-01T10:00:00Z", "2025-12-01T10:30:00Z");
        // Open for far longer than the retention: still happening, so still there.
        jdbc.update(
                """
                INSERT INTO bunching_events (
                    route_id, direction_id, vehicle_ids, peak_vehicles, span_m, expected_gap_m, service_gap_m,
                    basis, severity, peak_severity, sightings, first_seen_at, last_seen_at)
                VALUES (?, 1, ARRAY['c', 'd'], 2, 100, 1000, 2500, 'OBSERVED', 'MODERATE', 'MODERATE', 9,
                        '2025-01-01T00:00:00Z', '2026-02-20T11:59:00Z')
                """,
                ROUTE);

        Report report = retention.run(NOW);

        assertThat(report.bunchingEventsDeleted()).isEqualTo(1);
        assertThat(count("bunching_events")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM bunching_events WHERE route_id = ? AND resolved_at IS NULL", Integer.class, ROUTE))
                .isEqualTo(1);
    }

    @Test
    void resolvedAlertsPastTheirRetentionAreDeletedWithTheirEntitiesAndOnesInForceNever() {
        // 180 days before NOW is 24 August 2025.
        long old = alert("ret-old", "2025-08-01T10:00:00Z", "2025-08-01T10:30:00Z");
        long recent = alert("ret-recent", "2025-12-01T10:00:00Z", "2025-12-01T10:30:00Z");
        long open = alert("ret-open", "2025-01-01T00:00:00Z", null);

        Report report = retention.run(NOW);

        assertThat(report.alertsDeleted()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM service_alerts WHERE source_alert_id LIKE 'ret-%' ORDER BY id", Long.class))
                .containsExactly(recent, open);
        // What the deleted alert named went with it, and what the others named stayed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_alert_entities WHERE alert_id = ?", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_alert_entities WHERE alert_id = ?", Integer.class, open)).isOne();
    }

    private long alert(String sourceId, String firstSeen, String resolved) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO service_alerts (
                    source_alert_id, cause, effect, header, content_hash,
                    first_seen_at, last_seen_at, last_changed_at, resolved_at)
                VALUES (?, 1, 8, 'header', repeat('a', 64), ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                RETURNING id
                """,
                Long.class, sourceId, firstSeen, resolved == null ? "2026-02-20T11:00:00Z" : resolved, firstSeen, resolved);
        jdbc.update("INSERT INTO service_alert_entities (alert_id, route_id) VALUES (?, ?)", id, ROUTE);
        return id;
    }

    @Test
    void ingestionRunsPastTheirRetentionAreDeleted() {
        long old = run("2026-01-01T00:00:00Z");
        long recent = run("2026-02-10T00:00:00Z");

        Report report = retention.run(NOW);

        assertThat(report.ingestionRunsDeleted()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM ingestion_runs WHERE id IN (?, ?)", Long.class, old, recent))
                .containsExactly(recent);
    }

    @Test
    void hourlyRowsPastTheirRetentionAreDeleted() {
        // 365 days before NOW is 20 February 2025.
        hourly("2025-01-10T10:00:00Z");
        hourly("2025-12-10T10:00:00Z");

        Report report = retention.run(NOW);

        assertThat(report.hourlyRowsDeleted()).isEqualTo(1);
        assertThat(count("route_headway_hourly")).isEqualTo(1);
    }

    private void hourly(String hourStart) {
        jdbc.update(
                """
                INSERT INTO route_headway_hourly (
                    route_id, direction_id, hour_start, samples, excellent, normal, unstable, major_disruption, unknown,
                    variation_sum, variation_samples, gap_ratio_sum, gap_ratio_samples, bunching_samples,
                    first_sample_at, last_sample_at)
                VALUES (?, 0, ?::timestamptz, 60, 60, 0, 0, 0, 0, 0, 0, 0, 0, 0, ?::timestamptz, ?::timestamptz)
                """,
                ROUTE, hourStart, hourStart, hourStart);
    }

    // --- one step failing --------------------------------------------------------------------------------------

    @Test
    void aRunStillReferredToByAStoredPositionIsKeptAndTheFailureDoesNotStopTheOtherSteps() {
        // Not a state retention produces, since runs outlive positions. It is what a misconfiguration or a
        // restored backup could leave, and the cleanup must fail safe: leave the run, report it, carry on.
        long oldRun = run("2026-01-01T00:00:00Z");
        day("vehicle_observations", "2026-02-10");
        observation("ret-x", "2026-02-10T10:00:00Z", oldRun);
        event("2025-08-01T10:00:00Z", "2025-08-01T10:30:00Z");

        Report report = retention.run(NOW);

        assertThat(report.failedSteps()).contains("ingestion_runs");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_runs WHERE id = ?", Integer.class, oldRun)).isOne();
        // The steps either side of it still ran.
        assertThat(report.bunchingEventsDeleted()).isEqualTo(1);
    }

    @Test
    void aRunWhoseCutoffsPredateEverythingStoredRemovesNothing() {
        Report report = retention.run(Instant.parse("2020-01-01T00:00:00Z"));

        assertThat(report.partitionsDropped()).isEmpty();
        assertThat(report.failedSteps()).isEmpty();
    }
}
