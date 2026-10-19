package ca.trackline.api.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.trackline.api.alerts.AlertLifecycleService;
import ca.trackline.api.analytics.bunching.BunchingEventService;
import ca.trackline.api.analytics.health.HealthAnalysisMetrics;
import ca.trackline.api.analytics.health.RouteHealthService;
import ca.trackline.api.analytics.health.RouteHealthService.AnalysisResult;
import ca.trackline.api.analytics.history.HealthSampler;
import ca.trackline.api.analytics.progress.ProgressMetrics;
import ca.trackline.api.analytics.progress.RouteProgressProjector;
import ca.trackline.api.analytics.progress.RouteProgressSnapshot;
import ca.trackline.api.gtfs.realtime.FeedEntityValidator;
import ca.trackline.api.gtfs.realtime.FeedSnapshot;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.gtfs.realtime.RealtimeFeedParser;
import ca.trackline.api.gtfs.realtime.RealtimeProperties;
import ca.trackline.api.gtfs.realtime.TtcRealtimeClient;
import ca.trackline.api.livestate.InProcessSnapshotCache;
import ca.trackline.api.livestate.LiveStateStore;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import ca.trackline.api.stream.StreamHub;
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
    private final RouteProgressProjector progressProjector;
    private final ProgressMetrics progressMetrics;
    private final LiveStateStore liveState;
    private final InProcessSnapshotCache inProcessCache;
    private final StreamHub streamHub;
    private final AlertLifecycleService alertLifecycle;
    private final RouteHealthService routeHealth;
    private final BunchingEventService bunchingEvents;
    private final HealthSampler healthSampler;
    private final HealthAnalysisMetrics healthMetrics;
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
            RouteProgressProjector progressProjector,
            ProgressMetrics progressMetrics,
            LiveStateStore liveState,
            InProcessSnapshotCache inProcessCache,
            StreamHub streamHub,
            AlertLifecycleService alertLifecycle,
            RouteHealthService routeHealth,
            BunchingEventService bunchingEvents,
            HealthSampler healthSampler,
            HealthAnalysisMetrics healthMetrics,
            IngestionRunRecorder runRecorder,
            IngestionMetrics metrics,
            RealtimeProperties properties,
            JdbcTemplate jdbc,
            Clock clock) {
        this.client = client;
        this.parser = parser;
        this.observationWriter = observationWriter;
        this.progressProjector = progressProjector;
        this.progressMetrics = progressMetrics;
        this.liveState = liveState;
        this.inProcessCache = inProcessCache;
        this.streamHub = streamHub;
        this.alertLifecycle = alertLifecycle;
        this.routeHealth = routeHealth;
        this.bunchingEvents = bunchingEvents;
        this.healthSampler = healthSampler;
        this.healthMetrics = healthMetrics;
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
            if (feed == RealtimeFeed.ALERTS) {
                // Fetched again and the same: the alerts it carries were seen now, which
                // is what last_seen_at records, not merely when the publisher last edited.
                alertLifecycle.confirmUnchanged(Instant.now(clock));
            }
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
        RouteProgressSnapshot progress = RouteProgressSnapshot.unprojected();
        if (feed == RealtimeFeed.VEHICLE_POSITIONS && !snapshot.vehicles().isEmpty()) {
            Instant observedAt = Instant.now(clock);
            observationWriter.ensurePartitionFor(observedAt);

            // Placed before the readings are stored, so that "the previous reading"
            // is genuinely the one from an earlier cycle and not the one about to be
            // written.
            progress = project(snapshot);
            stored = observationWriter.write(snapshot.vehicles(), progress, runId, observedAt);

            // Published to both tiers every cycle. The in-process copy is written
            // unconditionally so this instance can still answer if Redis is the
            // thing that is down.
            inProcessCache.store(snapshot.vehicles(), snapshot.headerTimestamp());
            liveState.replaceVehicles(snapshot.vehicles(), snapshot.headerTimestamp());
            publishToStreams(snapshot);
            analyseRouteHealth(snapshot, progress, status);
        }

        if (feed == RealtimeFeed.ALERTS) {
            applyAlerts(snapshot, status);
        }

        lastHeaderTimestamp.put(feed, snapshot.headerTimestamp());
        lastEntityCount.put(feed, snapshot.acceptedCount());

        long durationMs = elapsedMs(startedNanos);
        runRecorder.updateDuration(runId, durationMs);
        metrics.recordCycle(feed, status, durationMs);
        metrics.recordEntities(feed, snapshot.acceptedCount(), snapshot.rejectedCount(), stored);

        log.info(
                "{} cycle status={} accepted={} rejected={} stored={} durationMs={} rejections={} placement={}",
                feed, status, snapshot.acceptedCount(), snapshot.rejectedCount(), stored, durationMs,
                snapshot.rejections(), progress.countsByStatus());

        return new IngestionCycleResult(feed, status, snapshot, stored, durationMs, null);
    }

    /**
     * Records the alerts the feed carries, unless the payload is not to be trusted.
     *
     * <p>Applying a payload resolves every stored alert missing from it, so a payload
     * that is wrong in the direction of "fewer" would end alerts that are still in force.
     * A suspect cycle is therefore not applied. The cost is delay, not loss: a feed that
     * really has cleared is suspect once, because the count fell, and trusted on the next
     * cycle, which compares against the new, lower count.
     */
    private void applyAlerts(FeedSnapshot snapshot, IngestionRunStatus status) {
        if (status != IngestionRunStatus.SUCCESS) {
            log.warn("ALERTS cycle was {}; stored alerts were left as they were", status);
            return;
        }
        AlertLifecycleService.Outcome outcome = alertLifecycle.apply(snapshot.alerts(), Instant.now(clock));
        log.info("ALERTS applied opened={} changed={} confirmed={} resolved={}",
                outcome.opened(), outcome.changed(), outcome.confirmed(), outcome.resolved());
    }

    /**
     * Works out each route's health from the placed vehicles.
     *
     * <p>Derived data, like the placement it is built on: a failure here must cost the
     * verdicts for one cycle and nothing else. The readings are stored and cached before this
     * runs, so an exception must not unwind past them. Skipped when projection failed, since
     * there is nothing placed to judge.
     */
    private void analyseRouteHealth(FeedSnapshot snapshot, RouteProgressSnapshot progress, IngestionRunStatus status) {
        if (progress.size() == 0) {
            return;
        }
        AnalysisResult result;
        try {
            result = routeHealth.analyse(snapshot.vehicles(), progress, snapshot.headerTimestamp());
        } catch (RuntimeException failure) {
            healthMetrics.recordFailure();
            log.warn("Could not analyse route health: {}", failure.toString());
            return;
        }
        recordBunching(result, status);
        recordHistory(result, status);
    }

    /**
     * Opens, continues and resolves the stored bunching events from an analysis.
     *
     * <p>Only for a cycle trusted as a whole. Applying an analysis resolves every open bunch it
     * does not contain, so one built from a fleet that suddenly shrank would end real bunches.
     * A suspect cycle is left out: the cost is a delay in resolving, never a wrongly resolved bunch.
     */
    private void recordBunching(AnalysisResult result, IngestionRunStatus status) {
        if (status != IngestionRunStatus.SUCCESS) {
            log.warn("{} cycle was {}; stored bunching events were left as they were", RealtimeFeed.VEHICLE_POSITIONS, status);
            return;
        }
        try {
            bunchingEvents.apply(result.spacing().bunches(), Instant.now(clock));
        } catch (RuntimeException failure) {
            healthMetrics.recordFailure();
            log.warn("Could not record bunching events: {}", failure.toString());
        }
    }

    /**
     * Records the verdicts for the history that reliability is read from.
     *
     * <p>Only for a cycle trusted as a whole: a suspect cycle's verdicts were reached from a feed that
     * was not believed, and recording them would put that into every figure derived afterwards. The cost
     * is a gap in the history, which the figures count as missing and do not fill. A failure here costs
     * the sample and nothing else.
     */
    private void recordHistory(AnalysisResult result, IngestionRunStatus status) {
        if (status != IngestionRunStatus.SUCCESS) {
            return;
        }
        try {
            healthSampler.sample(result.health(), Instant.now(clock));
        } catch (RuntimeException failure) {
            healthMetrics.recordFailure();
            log.warn("Could not record route health history: {}", failure.toString());
        }
    }

    /**
     * Pushes the cycle to connected clients.
     *
     * <p>Streaming is a view over data already stored and cached, so a failure here
     * must cost a client one update, never the cycle: the readings are written and
     * cached before this runs, and an exception must not unwind past them.
     */
    private void publishToStreams(FeedSnapshot snapshot) {
        try {
            streamHub.publish(snapshot.vehicles(), snapshot.headerTimestamp());
        } catch (RuntimeException failure) {
            log.warn("Could not publish the cycle to live streams: {}", failure.toString());
        }
    }

    /**
     * Places the vehicles on their routes, or reports that it could not.
     *
     * <p>Projection is derived data, and a failure in deriving it must never cost
     * the readings it was derived from. Those are the only record of where the
     * fleet was, and cannot be re-fetched: the publisher keeps no history. So a
     * failure is counted and logged, and the readings are stored unprojected —
     * which the schema records as such, rather than as a vehicle with nothing to
     * say.
     */
    private RouteProgressSnapshot project(FeedSnapshot snapshot) {
        try {
            return progressProjector.project(snapshot.vehicles());
        } catch (RuntimeException failure) {
            progressMetrics.recordFailure();
            log.warn("Could not place vehicles on their routes; storing readings unprojected: {}",
                    failure.toString());
            return RouteProgressSnapshot.unprojected();
        }
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
