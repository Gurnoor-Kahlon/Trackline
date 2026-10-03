package ca.trackline.api.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.trackline.api.gtfs.realtime.FeedEntityValidator;
import ca.trackline.api.gtfs.realtime.FeedSnapshot;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.gtfs.realtime.RealtimeFeedParser;
import ca.trackline.api.gtfs.realtime.RealtimeProperties;
import ca.trackline.api.gtfs.realtime.TtcRealtimeClient;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs one ingestion cycle for one feed.
 *
 * <p>A cycle fetches, parses, validates, decides whether the payload says anything
 * new, stores what is worth storing, and records what happened. Every outcome ends
 * in a row in {@code ingestion_runs}, including the boring ones: a feed that has
 * stopped changing and a feed that is failing look identical from the outside
 * unless the quiet case is recorded too.
 *
 * <p>Only one cycle per feed runs at a time. Two overlapping cycles would duplicate
 * work and race each other's deduplication state, and a slow fetch makes overlap
 * likely rather than theoretical.
 */
@Service
public class IngestionCycleService {

    private static final Logger log = LoggerFactory.getLogger(IngestionCycleService.class);

    private final TtcRealtimeClient client;
    private final RealtimeFeedParser parser;
    private final VehicleObservationWriter observationWriter;
    private final IngestionRunRecorder runRecorder;
    private final IngestionMetrics metrics;
    private final RealtimeProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** One guard per feed, so a slow trip-update fetch never blocks vehicle polling. */
    private final Map<RealtimeFeed, AtomicBoolean> running = new ConcurrentHashMap<>();

    /** Last header timestamp seen per feed, for detecting a payload that has not changed. */
    private final Map<RealtimeFeed, Instant> lastHeaderTimestamp = new ConcurrentHashMap<>();

    /** Accepted entity count per feed, for spotting an implausible collapse. */
    private final Map<RealtimeFeed, Integer> lastEntityCount = new ConcurrentHashMap<>();

    public IngestionCycleService(
            TtcRealtimeClient client,
            RealtimeFeedParser parser,
            VehicleObservationWriter observationWriter,
            IngestionRunRecorder runRecorder,
            IngestionMetrics metrics,
            RealtimeProperties properties,
            JdbcTemplate jdbc,
            Clock clock) {
        this.client = client;
        this.parser = parser;
        this.observationWriter = observationWriter;
        this.runRecorder = runRecorder;
        this.metrics = metrics;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return what the cycle did, including when it did nothing
     */
    public IngestionCycleResult runCycle(RealtimeFeed feed) {
        AtomicBoolean guard = running.computeIfAbsent(feed, key -> new AtomicBoolean());
        if (!guard.compareAndSet(false, true)) {
            metrics.recordSkipped(feed);
            log.debug("Skipping {} cycle: one is already running", feed);
            return IngestionCycleResult.skipped(feed);
        }

        Instant startedAt = Instant.now(clock);
        long startedNanos = System.nanoTime();
        try {
            return execute(feed, startedAt, startedNanos);
        } catch (RuntimeException failure) {
            long durationMs = elapsedMs(startedNanos);
            log.warn("{} cycle failed after {} ms: {}", feed, durationMs, failure.toString());
            metrics.recordCycle(feed, IngestionRunStatus.FAILED, durationMs);
            runRecorder.record(feed, startedAt, durationMs, IngestionRunStatus.FAILED, 0, 0, null,
                    shortReason(failure));
            return IngestionCycleResult.failed(feed, durationMs, shortReason(failure));
        } finally {
            guard.set(false);
        }
    }

    private IngestionCycleResult execute(RealtimeFeed feed, Instant startedAt, long startedNanos) {
        byte[] payload = client.fetch(feed);
        FeedSnapshot snapshot = parser.parse(feed, payload, validator(), Instant.now(clock));

        Instant previousHeader = lastHeaderTimestamp.get(feed);
        if (snapshot.headerTimestamp() != null && snapshot.headerTimestamp().equals(previousHeader)) {
            // The publisher has not produced anything new. Recording this rather than
            // reprocessing keeps the measured cycle duration honest and makes a
            // stalled publisher visible.
            long durationMs = elapsedMs(startedNanos);
            metrics.recordCycle(feed, IngestionRunStatus.NO_CHANGE, durationMs);
            runRecorder.record(feed, startedAt, durationMs, IngestionRunStatus.NO_CHANGE,
                    snapshot.acceptedCount(), snapshot.rejectedCount(), snapshot.headerTimestamp(), null);
            return IngestionCycleResult.noChange(feed, durationMs, snapshot);
        }

        IngestionRunStatus status = assessQuality(feed, snapshot);
        long runId = runRecorder.record(feed, startedAt, elapsedMs(startedNanos), status,
                snapshot.acceptedCount(), snapshot.rejectedCount(), snapshot.headerTimestamp(), null);

        int stored = 0;
        if (feed == RealtimeFeed.VEHICLE_POSITIONS && !snapshot.vehicles().isEmpty()) {
            Instant observedAt = Instant.now(clock);
            observationWriter.ensurePartitionFor(observedAt);
            stored = observationWriter.write(snapshot.vehicles(), runId, observedAt);
        }

        lastHeaderTimestamp.put(feed, snapshot.headerTimestamp());
        lastEntityCount.put(feed, snapshot.acceptedCount());

        long durationMs = elapsedMs(startedNanos);
        runRecorder.updateDuration(runId, durationMs);
        metrics.recordCycle(feed, status, durationMs);
        metrics.recordEntities(feed, snapshot.acceptedCount(), snapshot.rejectedCount(), stored);

        log.info(
                "{} cycle status={} accepted={} rejected={} stored={} durationMs={} rejections={}",
                feed, status, snapshot.acceptedCount(), snapshot.rejectedCount(), stored, durationMs,
                snapshot.rejections());

        return new IngestionCycleResult(feed, status, snapshot, stored, durationMs, null);
    }

    /**
     * Decides whether a successful parse should be trusted.
     *
     * <p>Two ways a feed misleads without failing: it starts emitting entities that
     * do not validate, or it suddenly carries far fewer than it did. Neither throws,
     * and both would otherwise replace good data with bad.
     */
    private IngestionRunStatus assessQuality(RealtimeFeed feed, FeedSnapshot snapshot) {
        if (snapshot.rejectionRate() > properties.suspectRejectionRate()) {
            log.warn("{} rejected {} of {} entities", feed, snapshot.rejectedCount(), snapshot.rawEntityCount());
            return IngestionRunStatus.SUSPECT;
        }

        Integer previousCount = lastEntityCount.get(feed);
        if (previousCount != null && previousCount > 0) {
            double ratio = (double) snapshot.acceptedCount() / previousCount;
            if (ratio < properties.suspectDropRatio()) {
                // Service genuinely thins out overnight, but not by this much between
                // one cycle and the next.
                log.warn("{} entity count fell from {} to {}", feed, previousCount, snapshot.acceptedCount());
                return IngestionRunStatus.SUSPECT;
            }
        }
        return IngestionRunStatus.SUCCESS;
    }

    /**
     * Routes in the active schedule, read fresh each cycle so an import takes effect
     * without a restart. Small enough (236 rows) that the query is not worth caching.
     */
    private FeedEntityValidator validator() {
        List<String> routeIds = jdbc.queryForList("SELECT route_id FROM routes", String.class);
        return new FeedEntityValidator(Set.copyOf(routeIds));
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * Clears the per-feed memory of the last header and entity count.
     *
     * <p>That memory is what makes the unchanged-payload and sudden-drop checks
     * work, so tests exercising those need to start from a known state.
     */
    void forgetFeedState() {
        lastHeaderTimestamp.clear();
        lastEntityCount.clear();
    }

    private static String shortReason(Throwable failure) {
        String message = failure.getMessage();
        String text = message == null ? failure.getClass().getSimpleName() : message;
        return text.length() <= 300 ? text : text.substring(0, 297) + "...";
    }
}
