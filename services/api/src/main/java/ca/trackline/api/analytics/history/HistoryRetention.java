package ca.trackline.api.analytics.history;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import ca.trackline.api.persistence.partition.DailyPartitions;
import ca.trackline.api.persistence.partition.DailyPartitions.Partition;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Removes history that has outlived its retention, so storage stays bounded.
 *
 * <p>The two large tables, stored vehicle positions and route health samples, are partitioned by day and
 * are trimmed by dropping partitions that have ended: a partition goes only when the whole range it covers
 * is older than the retention, so a position that is inside the window is never lost to rounding to a
 * day. The smaller tables are trimmed with a delete.
 *
 * <p>Each step is separate. One that fails, because a table is locked or a constraint objected, is logged and
 * counted and does not stop the others; whatever it left is removed by the next run.
 */
@Service
public class HistoryRetention {

    private static final Logger log = LoggerFactory.getLogger(HistoryRetention.class);

    /** Longest a drop waits for a lock. Ingestion writes constantly and must not be held up behind it. */
    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(3);

    private static final String REMOVED = "trackline.history.retention.removed";
    private static final String FAILURES = "trackline.history.retention.failures";

    private final JdbcTemplate jdbc;
    private final DailyPartitions partitions;
    private final HistoryProperties properties;
    private final MeterRegistry registry;

    public HistoryRetention(
            JdbcTemplate jdbc, DailyPartitions partitions, HistoryProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.partitions = partitions;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * What one run removed.
     *
     * @param partitionsDropped names of the partitions dropped
     * @param failedSteps       the steps that did not complete, by name
     */
    public record Report(
            List<String> partitionsDropped,
            int bunchingEventsDeleted,
            int alertsDeleted,
            int ingestionRunsDeleted,
            int hourlyRowsDeleted,
            List<String> failedSteps) {

        public Report {
            partitionsDropped = List.copyOf(partitionsDropped);
            failedSteps = List.copyOf(failedSteps);
        }
    }

    /** Runs every step against the retention as configured. */
    public Report run(Instant now) {
        HistoryProperties.Retention retention = properties.retention();
        List<String> dropped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // Positions first: every one refers to the ingestion run that fetched it, so the runs can only go once
        // the positions have.
        dropEnded("vehicle_observations", now.minus(retention.observations()), dropped, failed);
        dropEnded(HealthSampler.TABLE, now.minus(retention.samples()), dropped, failed);

        int events = attempt("bunching_events", failed, () -> jdbc.update(
                "DELETE FROM bunching_events WHERE resolved_at IS NOT NULL AND resolved_at < ?",
                Timestamp.from(now.minus(retention.bunchingEvents()))));
        // The entities an alert named go with it, by the foreign key's cascade.
        int alerts = attempt("service_alerts", failed, () -> jdbc.update(
                "DELETE FROM service_alerts WHERE resolved_at IS NOT NULL AND resolved_at < ?",
                Timestamp.from(now.minus(retention.alerts()))));
        int runs = attempt("ingestion_runs", failed, () -> jdbc.update(
                "DELETE FROM ingestion_runs WHERE started_at < ?",
                Timestamp.from(now.minus(retention.ingestionRuns()))));
        int hourly = attempt("route_headway_hourly", failed, () -> jdbc.update(
                "DELETE FROM route_headway_hourly WHERE hour_start < ?",
                Timestamp.from(now.minus(retention.hourly()))));

        registry.counter(REMOVED, "kind", "partitions").increment(dropped.size());
        registry.counter(REMOVED, "kind", "bunching_events").increment(events);
        registry.counter(REMOVED, "kind", "service_alerts").increment(alerts);
        registry.counter(REMOVED, "kind", "ingestion_runs").increment(runs);
        registry.counter(REMOVED, "kind", "route_headway_hourly").increment(hourly);

        Report report = new Report(dropped, events, alerts, runs, hourly, failed);
        log.info(
                "History retention dropped={} bunchingEvents={} alerts={} ingestionRuns={} hourlyRows={} failed={}",
                dropped, events, alerts, runs, hourly, failed);
        return report;
    }

    private void dropEnded(String parent, Instant cutoff, List<String> dropped, List<String> failed) {
        List<Partition> ended;
        try {
            ended = partitions.list(parent).stream()
                    .filter(partition -> !partition.upperBound().isAfter(cutoff))
                    .toList();
        } catch (RuntimeException failure) {
            recordFailure(parent, parent, failed, failure);
            return;
        }
        for (Partition partition : ended) {
            try {
                partitions.drop(parent, partition, LOCK_TIMEOUT);
                dropped.add(partition.name());
            } catch (RuntimeException failure) {
                recordFailure(parent, partition.name(), failed, failure);
            }
        }
    }

    private int attempt(String step, List<String> failed, IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (RuntimeException failure) {
            recordFailure(step, step, failed, failure);
            return 0;
        }
    }

    /**
     * @param table what was being trimmed, which labels the counter; a partition's own name would not do,
     *              since there is a new one every day
     * @param step  what failed, for the log and the report
     */
    private void recordFailure(String table, String step, List<String> failed, RuntimeException failure) {
        failed.add(step);
        registry.counter(FAILURES, "table", table).increment();
        log.warn("History retention could not complete {}: {}", step, failure.toString());
    }
}
