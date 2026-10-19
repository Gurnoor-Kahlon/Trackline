package ca.trackline.api.analytics.history;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.persistence.partition.DailyPartitions;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Records route health as it was, about once a minute.
 *
 * <p>The analysis runs every cycle, about every twenty seconds, and a verdict seldom changes between
 * two of them; a row per cycle would triple the largest table for no added information. A snapshot is
 * recorded when the interval has passed since the last one.
 *
 * <p>One row per route direction, not per route: the route's state is the worse of its directions, and
 * the direction is where the figures behind it live. A direction that could not be judged is recorded
 * too, as {@code UNKNOWN}, so that the share of time a route was not judged is a measured figure and
 * not an absence.
 *
 * <p>The caller decides whether a cycle deserves recording. A cycle the ingestion judged suspect built
 * its verdicts from a feed it did not trust, and recording them would put that into every figure
 * derived later.
 */
@Service
public class HealthSampler {

    private static final Logger log = LoggerFactory.getLogger(HealthSampler.class);

    static final String TABLE = "route_health_samples";

    private static final String SAMPLES = "trackline.history.samples";

    private final JdbcTemplate jdbc;
    private final DailyPartitions partitions;
    private final HistoryProperties properties;
    private final MeterRegistry registry;

    /** Days whose partition is known to exist, so a sample does not ask the catalogue every time. */
    private final Set<LocalDate> ensured = new HashSet<>();
    private Instant lastSampledAt;

    public HealthSampler(
            JdbcTemplate jdbc, DailyPartitions partitions, HistoryProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.partitions = partitions;
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * @param snapshot the verdicts to record
     * @param now      when the analysis ran
     * @return how many rows were written; zero when it is not yet time for the next sample
     */
    public synchronized int sample(HealthSnapshot snapshot, Instant now) {
        if (lastSampledAt != null && now.isBefore(lastSampledAt.plus(properties.sampleInterval()))) {
            return 0;
        }

        List<Object[]> rows = new ArrayList<>();
        Timestamp at = Timestamp.from(now);
        for (RouteHealth route : snapshot.routes()) {
            for (DirectionHealth direction : route.directions()) {
                rows.add(row(route.routeId(), direction, at));
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }

        long started = System.nanoTime();
        ensurePartitions(now);
        int[] written = jdbc.batchUpdate(
                """
                INSERT INTO route_health_samples (
                    route_id, direction_id, sampled_at, state, confidence, reason_codes, vehicles,
                    variation, gap_ratio, supply_ratio, bunching_clusters, basis)
                VALUES (?, ?, ?, ?, ?, string_to_array(?, ','), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (route_id, direction_id, sampled_at) DO NOTHING
                """,
                rows);

        // Set only after the write, so a failed one is tried again at the next cycle and not after a minute.
        lastSampledAt = now;
        // Zero means the row was already there. The driver may report "succeeded, count unknown" instead of one.
        int count = 0;
        for (int outcome : written) {
            count += outcome != 0 ? 1 : 0;
        }
        registry.counter(SAMPLES).increment(count);
        // About once a minute, so cheap to log, and it is where the cost of recording is measured from.
        log.info("History sampled rows={} durationMs={}", count, (System.nanoTime() - started) / 1_000_000);
        return count;
    }

    private static Object[] row(String routeId, DirectionHealth direction, Timestamp at) {
        HealthMetrics metrics = direction.metrics();
        // Reason codes are fixed upper-case names with no commas, so a delimited string round-trips exactly
        // and avoids building a JDBC array for every row.
        String reasons = String.join(",", direction.reasons().stream().map(Enum::name).toList());
        return new Object[] {
            routeId,
            direction.directionId(),
            at,
            direction.state().name(),
            direction.confidence().name(),
            reasons,
            metrics.vehicles(),
            metrics.variation(),
            metrics.gapRatio(),
            metrics.supplyRatio(),
            metrics.bunchingClusters(),
            metrics.basis() == null ? null : metrics.basis().name()
        };
    }

    /** Today's partition and tomorrow's, so a sample taken just before midnight UTC still has somewhere to go. */
    private void ensurePartitions(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        ensured.removeIf(day -> day.isBefore(today));
        for (LocalDate day : List.of(today, today.plusDays(1))) {
            if (!ensured.contains(day)) {
                partitions.ensure(TABLE, day);
                ensured.add(day);
            }
        }
    }

    /** Forgets when the last sample was and which partitions exist. For tests that need a fresh sampler. */
    public synchronized void forget() {
        lastSampledAt = null;
        ensured.clear();
    }
}
