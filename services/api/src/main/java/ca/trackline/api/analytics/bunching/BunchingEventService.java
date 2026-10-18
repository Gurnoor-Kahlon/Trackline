package ca.trackline.api.analytics.bunching;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.trackline.api.analytics.bunching.BunchingConfirmer.Confirmed;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingTracker.Continued;
import ca.trackline.api.analytics.bunching.BunchingTracker.Missed;
import ca.trackline.api.analytics.bunching.BunchingTracker.Open;
import ca.trackline.api.analytics.bunching.BunchingTracker.Plan;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the stored bunching events in step with what each analysis finds.
 *
 * <p>Each call is one transaction, so an analysis either applies completely or leaves the
 * events as they were: a failure halfway cannot resolve some bunches on the strength of an
 * analysis it then failed to finish writing.
 *
 * <p>The caller decides whether an analysis deserves to be applied. One that is wrong in the
 * direction of "fewer bunches" would resolve real ones, so a cycle the ingestion judged
 * suspect must not be handed over.
 */
@Service
public class BunchingEventService {

    private static final String EVENTS = "trackline.analytics.bunching.events";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BunchingTracker tracker;
    private final BunchingConfirmer confirmer;
    private final BunchingProperties properties;
    private final MeterRegistry registry;

    public BunchingEventService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            BunchingProperties properties,
            MeterRegistry registry) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.tracker = new BunchingTracker(properties);
        this.confirmer = new BunchingConfirmer(properties);
        this.properties = properties;
        this.registry = registry;
    }

    /** Forgets the packs waiting to be confirmed. For tests, which need a service with no memory of earlier ones. */
    public void forgetPending() {
        confirmer.forget();
    }

    /** What one analysis did to the stored events. */
    public record Outcome(int opened, int continued, int missed, int resolved) {
    }

    /**
     * @param found every bunch the analysis found, across all routes
     * @param now   when it was found
     */
    public Outcome apply(List<BunchingCandidate> found, Instant now) {
        Outcome outcome = transaction.execute(status -> applyInTransaction(found, now));
        registry.counter(EVENTS, "outcome", "opened").increment(outcome.opened());
        registry.counter(EVENTS, "outcome", "resolved").increment(outcome.resolved());
        return outcome;
    }

    private Outcome applyInTransaction(List<BunchingCandidate> found, Instant now) {
        List<Open> open = jdbc.query(
                "SELECT id, route_id, direction_id, vehicle_ids, missed_cycles FROM bunching_events WHERE resolved_at IS NULL",
                (rows, index) -> new Open(
                        rows.getLong("id"),
                        rows.getString("route_id"),
                        rows.getShort("direction_id"),
                        Set.of((String[]) rows.getArray("vehicle_ids").getArray()),
                        rows.getInt("missed_cycles")));

        // A new pack waits to be seen again before it is recorded; one that continues an open event does not,
        // since a brief absence must not be able to end that event.
        List<Confirmed> confirmed = confirmer.update(found, candidate -> continuesOpen(open, candidate), now);
        Map<BunchingCandidate, Confirmed> byCandidate = new IdentityHashMap<>();
        confirmed.forEach(entry -> byCandidate.put(entry.candidate(), entry));

        Plan plan = tracker.reconcile(open, confirmed.stream().map(Confirmed::candidate).toList());
        Timestamp at = Timestamp.from(now);

        for (BunchingCandidate candidate : plan.toOpen()) {
            Confirmed run = byCandidate.get(candidate);
            Timestamp firstSeen = Timestamp.from(run.firstSeenAt());
            jdbc.update(
                    connection -> {
                        var statement = connection.prepareStatement(
                                """
                                INSERT INTO bunching_events (
                                    route_id, direction_id, vehicle_ids, peak_vehicles, span_m, expected_gap_m,
                                    service_gap_m, basis, severity, peak_severity, sightings, first_seen_at,
                                    last_seen_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """);
                        statement.setString(1, candidate.routeId());
                        statement.setShort(2, candidate.directionId());
                        statement.setArray(3, ids(connection, candidate));
                        statement.setInt(4, candidate.vehicles());
                        statement.setDouble(5, candidate.spanMetres());
                        statement.setDouble(6, candidate.expectedGapMetres());
                        statement.setDouble(7, candidate.serviceGapMetres());
                        statement.setString(8, candidate.basis().name());
                        statement.setString(9, candidate.severity().name());
                        statement.setString(10, candidate.severity().name());
                        statement.setInt(11, run.sightings());
                        statement.setTimestamp(12, firstSeen);
                        statement.setTimestamp(13, at);
                        return statement;
                    });
        }

        for (Continued seen : plan.continued()) {
            BunchingCandidate candidate = seen.candidate();
            jdbc.update(
                    connection -> {
                        var statement = connection.prepareStatement(
                                """
                                UPDATE bunching_events
                                SET vehicle_ids = ?, peak_vehicles = GREATEST(peak_vehicles, ?), span_m = ?,
                                    expected_gap_m = ?, service_gap_m = ?, basis = ?, severity = ?,
                                    peak_severity = CASE WHEN ? = 'SEVERE' OR peak_severity = 'SEVERE'
                                                         THEN 'SEVERE' ELSE 'MODERATE' END,
                                    sightings = sightings + 1, missed_cycles = 0,
                                    last_seen_at = GREATEST(last_seen_at, ?)
                                WHERE id = ?
                                """);
                        statement.setArray(1, ids(connection, candidate));
                        statement.setInt(2, candidate.vehicles());
                        statement.setDouble(3, candidate.spanMetres());
                        statement.setDouble(4, candidate.expectedGapMetres());
                        statement.setDouble(5, candidate.serviceGapMetres());
                        statement.setString(6, candidate.basis().name());
                        statement.setString(7, candidate.severity().name());
                        statement.setString(8, candidate.severity().name());
                        statement.setTimestamp(9, at);
                        statement.setLong(10, seen.id());
                        return statement;
                    });
        }

        for (Missed absent : plan.missed()) {
            jdbc.update("UPDATE bunching_events SET missed_cycles = ? WHERE id = ?", absent.missedCycles(), absent.id());
        }

        // GREATEST keeps the stored order intact if the clock has stepped backwards: a resolution can
        // never claim to be earlier than the last time the bunch was seen.
        for (Long id : plan.toResolve()) {
            jdbc.update(
                    "UPDATE bunching_events SET resolved_at = GREATEST(?, last_seen_at), missed_cycles = missed_cycles + 1 WHERE id = ?",
                    at, id);
        }

        return new Outcome(plan.toOpen().size(), plan.continued().size(), plan.missed().size(), plan.toResolve().size());
    }

    /** Whether a pack is the same bunch as one already open: same direction, enough shared vehicles. */
    private boolean continuesOpen(List<Open> open, BunchingCandidate candidate) {
        return open.stream()
                .filter(event -> event.routeId().equals(candidate.routeId())
                        && event.directionId() == candidate.directionId())
                .anyMatch(event -> candidate.vehicleIds().stream().filter(event.vehicleIds()::contains).count()
                        >= properties.minSharedVehicles());
    }

    private static Array ids(java.sql.Connection connection, BunchingCandidate candidate) throws java.sql.SQLException {
        return connection.createArrayOf("text", candidate.vehicleIds().toArray(new String[0]));
    }
}
